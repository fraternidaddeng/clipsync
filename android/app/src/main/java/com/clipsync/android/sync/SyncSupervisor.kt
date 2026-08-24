package com.clipsync.android.sync

import com.clipsync.android.pairing.PairingStore
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

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

/** Connection lifecycle as the notification/UI layer sees it. Never carries clipboard text. */
sealed interface SyncConnectionState {
    data object NotPaired : SyncConnectionState

    data object Connecting : SyncConnectionState

    data class Connected(val peerDisplayName: String) : SyncConnectionState

    data class WaitingRetry(val attempt: Int, val delayMs: Long) : SyncConnectionState
}

/**
 * Owns the reconnect loop: reads the paired peer, dials each pairing host in preference order,
 * runs one [SyncEngine] session, and retries with exponential backoff. A session that reached
 * mutual authentication resets the backoff; a certificate pin mismatch never fails over to the
 * next host because a changed certificate must block, not be retried around.
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
) {
    private val mutableState = MutableStateFlow<SyncConnectionState>(SyncConnectionState.NotPaired)

    val state: StateFlow<SyncConnectionState> = mutableState.asStateFlow()

    /** Runs until the calling coroutine is cancelled (the foreground service scope). */
    suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val peer = pairing.peer()
            val secret = pairing.pairSecret()
            if (peer == null || secret == null) {
                mutableState.value = SyncConnectionState.NotPaired
                delay(unpairedPollMs)
                attempt = 0
                continue
            }

            mutableState.value = SyncConnectionState.Connecting
            val transport = connectAnyHost(peer)
            if (transport == null) {
                secret.fill(0)
                attempt = waitBeforeRetry(attempt)
                continue
            }

            mutableState.value = SyncConnectionState.Connected(peer.displayName)
            val engine = SyncEngine(
                repository = repository,
                config = SyncSessionConfig(
                    localDeviceId = pairing.localDeviceId(),
                    peerDeviceId = peer.deviceId,
                    trustEpoch = peer.trustEpoch,
                    clientVersion = clientVersion,
                    nowMs = nowMs,
                    peerStillTrusted = {
                        val current = pairing.peer()
                        current != null && current.deviceId == peer.deviceId && current.trustEpoch == peer.trustEpoch
                    },
                ),
                pairSecret = secret,
                onRemoteClipsCommitted = onRemoteClipsCommitted,
            )
            secret.fill(0)
            val result = try {
                engine.run(transport)
            } finally {
                transport.dispose()
            }

            attempt = if (result.authenticated) 0 else attempt
            if (!currentCoroutineContext().isActive) {
                break
            }
            attempt = waitBeforeRetry(attempt)
        }
    }

    /** Tries every pairing host in preference order; null when none accepted a connection. */
    private suspend fun connectAnyHost(peer: com.clipsync.android.pairing.PairedPeer): SyncTransport? {
        for (host in peer.hosts) {
            try {
                return connector.connect(host, peer.port, peer.certSha256)
            } catch (_: PinMismatchException) {
                // Wrong certificate is a trust decision, not a connectivity problem: stop the
                // whole attempt so the user re-pairs instead of silently probing other hosts.
                return null
            } catch (_: IOException) {
                continue
            }
        }
        return null
    }

    private suspend fun waitBeforeRetry(attempt: Int): Int {
        val delayMs = backoff.nextDelayMs(attempt)
        mutableState.value = SyncConnectionState.WaitingRetry(attempt, delayMs)
        delay(delayMs)
        return attempt + 1
    }
}
