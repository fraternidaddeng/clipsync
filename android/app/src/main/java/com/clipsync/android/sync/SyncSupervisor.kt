package com.clipsync.android.sync

import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** Deterministically testable exponential backoff with bounded jitter. */
class ReconnectBackoff(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** Delay before retry number [attempt] (0-based), exponentially grown, jittered, capped. */
    fun nextDelayMs(attempt: Int): Long {
        var base = initialDelayMs.toDouble()
        for (step in 0 until attempt) {
            base *= multiplier
            if (base >= maxDelayMs) {
                break
            }
        }
        val capped = min(base, maxDelayMs.toDouble())
        val jitter = 1.0 + jitterRatio * (random.nextDouble() * 2.0 - 1.0)
        return (capped * jitter).toLong().coerceAtLeast(0)
    }
}

/** Which link carries an authenticated session: the normal IP path or the bt1 fallback. */
enum class SyncTransportKind { IP, BLUETOOTH }

/** Connection lifecycle as the notification/UI layer sees it. Never carries clipboard text. */
sealed interface SyncConnectionState {
    data object NotPaired : SyncConnectionState

    data object Connecting : SyncConnectionState

    data class Connected(
        val peerDisplayName: String,
        val transport: SyncTransportKind = SyncTransportKind.IP,
    ) : SyncConnectionState

    data class WaitingRetry(
        val attempt: Int,
        val delayMs: Long,
    ) : SyncConnectionState
}

/**
 * Dials the bt1 Bluetooth fallback (ADR 0005) and completes its handshake. Returns null when
 * the fallback is off, its prerequisites are missing (permission, adapter, selected bonded
 * device), or the dial/handshake failed — the supervisor treats every null the same way it
 * treats an unreachable host. Implementations must not retain [pairSecret].
 */
fun interface BluetoothFallbackDialer {
    suspend fun dial(
        localDeviceId: String,
        peer: PairedPeer,
        pairSecret: ByteArray,
    ): SyncTransport?
}

/**
 * Owns the reconnect loop: reads the paired peer, dials each pairing host in preference order,
 * runs one [SyncEngine] session, and retries with exponential backoff. A session that reached
 * mutual authentication resets the backoff; a certificate pin mismatch never fails over to the
 * next host because a changed certificate must block, not be retried around. A network-available
 * signal ([nudgeReconnect]) cuts the current wait short without resetting the schedule.
 *
 * Bluetooth fallback (ADR 0005): when every IP candidate is unreachable and a
 * [BluetoothFallbackDialer] is wired, one bt1 dial follows in the same cycle. IP always wins:
 * a Bluetooth session keeps probing the IP path at a low cadence (plus on every reconnect
 * nudge), and the first successful IP dial closes the Bluetooth session and carries the next
 * session. A pin mismatch still stops everything — trust failures never fall back.
 */
class SyncSupervisor(
    private val pairing: PairingStore,
    private val repository: SyncRepository,
    private val connector: SyncConnector,
    private val clientVersion: String,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val unpairedPollMs: Long = 15_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onRemoteClipsCommitted: (List<RemoteClipApplied>) -> Unit = {},
    /** Pause/private gate for outbound announces; passed through to every session. */
    private val outboundAllowed: () -> Boolean = { true },
    /**
     * When true, each dial attempt tries protocol v2 (`/v2/peer/sync`, image sync) first and
     * falls back to v1 when the peer's listener refuses the v2 handshake. Re-read per attempt
     * so flipping the preference applies on the next (re)connect.
     */
    private val imageSyncEnabled: () -> Boolean = { false },
    /**
     * Fired once per lockout episode when the peer answers with RATE_LIMITED before this
     * device authenticated — the Windows listener throttles repeated auth failures, and the
     * user must see that instead of a silent retry loop (mirrors the Windows tray bubble).
     * Re-armed only after a session authenticates again. Never carries clipboard content.
     */
    private val onAuthThrottled: () -> Unit = {},
    /** bt1 fallback dial, tried once per cycle after every IP host failed; null = not wired. */
    private val bluetoothDialer: BluetoothFallbackDialer? = null,
    /** How often a live Bluetooth session re-probes the IP path for the switch back. */
    private val ipProbeIntervalMs: Long = 30_000,
) {
    private val mutableState = MutableStateFlow<SyncConnectionState>(SyncConnectionState.NotPaired)
    private var throttleAnnounced = false

    /** The engine of the currently running session, if any; closed by [restartSession]. */
    @Volatile
    private var activeEngine: SyncEngine? = null

    /** An IP socket the Bluetooth-session probe already connected; the next session uses it. */
    private var pendingIpTransport: ConnectedTransport? = null

    // Conflated so a burst of network-available callbacks collapses into one early retry.
    private val reconnectNudges = Channel<Unit>(Channel.CONFLATED)

    val state: StateFlow<SyncConnectionState> = mutableState.asStateFlow()

    /**
     * Asks the loop to skip the remainder of the current backoff wait and dial now. Called when
     * the platform reports a network became available, so a link that just came up is tried
     * immediately instead of sitting out the rest of a wait that can reach 60 s. The attempt
     * counter is deliberately not reset: a flapping network still faces growing delays between
     * failed dials, and only a session that reached mutual authentication restarts the schedule.
     */
    fun nudgeReconnect() {
        reconnectNudges.trySend(Unit)
    }

    /**
     * Closes the current session (if any) so the next dial re-reads its connection-time
     * inputs — most importantly [imageSyncEnabled], whose protocol-version choice (v2 with
     * image frames vs text-only v1) is fixed at dial time. Without this, flipping the image
     * sync preference changes nothing until the session happens to drop, which on a stable
     * network can be arbitrarily far away. A close of an authenticated session resets the
     * backoff, so the redial follows within about a second; when the loop is sitting out a
     * backoff wait instead of running a session, the nudge cuts that wait short.
     */
    fun restartSession() {
        activeEngine?.requestClose()
        reconnectNudges.trySend(Unit)
    }

    /** Runs until the calling coroutine is cancelled (the foreground service scope). */
    suspend fun run() {
        try {
            runLoop()
        } finally {
            pendingIpTransport?.transport?.dispose()
            pendingIpTransport = null
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val peer = pairing.peer()
            val secret = pairing.pairSecret()
            if (peer == null || secret == null) {
                // A probe-won IP socket must not outlive the pairing it belongs to.
                pendingIpTransport?.transport?.dispose()
                pendingIpTransport = null
                mutableState.value = SyncConnectionState.NotPaired
                delay(unpairedPollMs)
                attempt = 0
                continue
            }

            mutableState.value = SyncConnectionState.Connecting
            val connected = takePendingIpTransport() ?: dialOnce(peer, secret)
            if (connected == null) {
                secret.fill(0)
                attempt = waitBeforeRetry(attempt)
                continue
            }

            mutableState.value = SyncConnectionState.Connected(peer.displayName, connected.kind)
            val engine =
                SyncEngine(
                    repository = repository,
                    config =
                        SyncSessionConfig(
                            localDeviceId = pairing.localDeviceId(),
                            peerDeviceId = peer.deviceId,
                            trustEpoch = peer.trustEpoch,
                            clientVersion = clientVersion,
                            protocolVersion = connected.protocolVersion,
                            nowMs = nowMs,
                            peerStillTrusted = {
                                val current = pairing.peer()
                                current != null &&
                                    current.deviceId == peer.deviceId &&
                                    current.trustEpoch == peer.trustEpoch
                            },
                            outboundAllowed = outboundAllowed,
                        ),
                    pairSecret = secret,
                    onRemoteClipsCommitted = onRemoteClipsCommitted,
                )
            secret.fill(0)
            activeEngine = engine
            val result =
                try {
                    runSession(engine, connected, peer)
                } finally {
                    activeEngine = null
                    connected.transport.dispose()
                }

            if (result.authenticated) {
                attempt = 0
                throttleAnnounced = false
            } else if (result.errorCode == SyncErrorCodes.RATE_LIMITED && !throttleAnnounced) {
                // Announce the transition into the throttled state once, not on every
                // backoff retry inside the same 30-second lockout window.
                throttleAnnounced = true
                onAuthThrottled()
            }
            if (!currentCoroutineContext().isActive) {
                break
            }
            // Coming off a Bluetooth session with an IP socket already won by the probe:
            // start the IP session immediately instead of sitting out a backoff wait.
            if (pendingIpTransport != null) {
                continue
            }
            attempt = waitBeforeRetry(attempt)
        }
    }

    /**
     * Runs one session; a Bluetooth session additionally keeps a low-cadence IP probe alive.
     * When the probe wins an IP socket it parks it in [pendingIpTransport] and disposes the
     * Bluetooth transport, so the engine returns and the loop switches back to IP at once —
     * one active session at any moment, never two.
     */
    private suspend fun runSession(
        engine: SyncEngine,
        connected: ConnectedTransport,
        peer: PairedPeer,
    ): SyncSessionResult =
        coroutineScope {
            val probe =
                if (connected.kind == SyncTransportKind.BLUETOOTH) {
                    launch { probeIpWhileOnBluetooth(peer, connected.transport) }
                } else {
                    null
                }
            try {
                engine.run(connected.transport)
            } finally {
                probe?.cancel()
            }
        }

    private suspend fun probeIpWhileOnBluetooth(
        peer: PairedPeer,
        bluetoothTransport: SyncTransport,
    ) {
        while (currentCoroutineContext().isActive) {
            // A network-available nudge tries the IP path right away; otherwise the timer does.
            withTimeoutOrNull(ipProbeIntervalMs) { reconnectNudges.receive() }
            when (val outcome = connectAnyHost(peer)) {
                is IpDialOutcome.Connected -> {
                    pendingIpTransport = outcome.transport
                    bluetoothTransport.dispose()
                    return
                }
                IpDialOutcome.PinMismatch -> {
                    // The IP path presents a wrong certificate: never switch onto it. The
                    // Bluetooth session stays (its trust is the pair secret), probing stops.
                    return
                }
                IpDialOutcome.Unreachable -> {}
            }
        }
    }

    private fun takePendingIpTransport(): ConnectedTransport? {
        val pending = pendingIpTransport
        pendingIpTransport = null
        return pending
    }

    /** IP candidates first; the bt1 fallback only after every host failed on connectivity. */
    private suspend fun dialOnce(
        peer: PairedPeer,
        secret: ByteArray,
    ): ConnectedTransport? =
        when (val outcome = connectAnyHost(peer)) {
            is IpDialOutcome.Connected -> outcome.transport
            // Wrong certificate is a trust decision, not a connectivity problem: no Bluetooth
            // fallback either, so the user re-pairs instead of the failure being masked.
            IpDialOutcome.PinMismatch -> null
            IpDialOutcome.Unreachable -> dialBluetooth(peer, secret)
        }

    private suspend fun dialBluetooth(
        peer: PairedPeer,
        secret: ByteArray,
    ): ConnectedTransport? {
        val dialer = bluetoothDialer ?: return null
        val transport =
            try {
                dialer.dial(pairing.localDeviceId(), peer, secret)
            } catch (_: IOException) {
                null
            }
        return transport?.let {
            // bt1 carries protocol v1 only: image capability is never declared on Bluetooth
            // (ADR 0005 §4), so the session dials the engine at version 1 unconditionally.
            ConnectedTransport(it, protocolVersion = 1, kind = SyncTransportKind.BLUETOOTH)
        }
    }

    /** A dialed socket together with the wire version and link kind it was accepted on. */
    private class ConnectedTransport(
        val transport: SyncTransport,
        val protocolVersion: Int,
        val kind: SyncTransportKind = SyncTransportKind.IP,
    )

    private sealed interface IpDialOutcome {
        class Connected(
            val transport: ConnectedTransport,
        ) : IpDialOutcome

        data object PinMismatch : IpDialOutcome

        data object Unreachable : IpDialOutcome
    }

    /**
     * Tries every pairing host in preference order. With image sync on, each host is dialed
     * on v2 first and once more on v1 when the v2 handshake is refused (a v1-only listener
     * rejects `/v2/peer/sync` before upgrade); a certificate pin mismatch stops everything
     * because trust, not connectivity, failed.
     */
    private suspend fun connectAnyHost(peer: PairedPeer): IpDialOutcome {
        val versions = if (imageSyncEnabled()) listOf(2, 1) else listOf(1)
        for (host in peer.hosts) {
            for (version in versions) {
                try {
                    return IpDialOutcome.Connected(
                        ConnectedTransport(
                            connector.connect(host, peer.port, peer.certSha256, version),
                            version,
                        ),
                    )
                } catch (_: PinMismatchException) {
                    // Wrong certificate is a trust decision, not a connectivity problem: stop
                    // the whole attempt so the user re-pairs instead of silently probing on.
                    return IpDialOutcome.PinMismatch
                } catch (_: IOException) {
                    continue
                }
            }
        }
        return IpDialOutcome.Unreachable
    }

    private suspend fun waitBeforeRetry(attempt: Int): Int {
        // A nudge that arrived while connecting/connected reported a network the attempt that
        // just failed already saw; drain it so only events during this wait cut it short.
        reconnectNudges.tryReceive()
        val delayMs = backoff.nextDelayMs(attempt)
        mutableState.value = SyncConnectionState.WaitingRetry(attempt, delayMs)
        withTimeoutOrNull(delayMs) { reconnectNudges.receive() }
        return attempt + 1
    }
}
