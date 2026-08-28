package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val PEER_ID = "11111111-1111-4111-8111-111111111111"
private const val HOST_A = "192.168.1.23"
private const val HOST_B = "10.0.11.7"

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSupervisorTest {
    private class ScriptedConnector(
        var behavior: (String) -> SyncTransport,
    ) : SyncConnector {
        val calls = mutableListOf<String>()
        val versions = mutableListOf<Int>()

        override suspend fun connect(
            host: String,
            port: Int,
            certSha256: String,
            protocolVersion: Int,
        ): SyncTransport {
            calls.add(host)
            versions.add(protocolVersion)
            return behavior(host)
        }
    }

    /**
     * A socket that connects and is gone before the handshake: the engine sees an immediate
     * Closed frame and the session ends unauthenticated — the weak-network flap shape.
     */
    private class InstantDropTransport : SyncTransport {
        override suspend fun receive(): TransportFrame = TransportFrame.Closed

        override suspend fun send(text: String) = Unit

        override suspend fun close(
            code: Int,
            reason: String,
        ) = Unit

        override fun dispose() = Unit
    }

    private fun pairedStore(): PairingStore {
        val store = PairingStore(FakeKeyValueStore(), FakeSecretProtector())
        store.savePeer(
            qr =
                PairingQrPayload(
                    kind = PairingDocumentKinds.QR,
                    version = 1,
                    hosts = listOf(HOST_A, HOST_B),
                    port = 47_654,
                    deviceId = PEER_ID,
                    displayName = "DESKTOP-WIN",
                    certSha256 = "ab".repeat(32),
                    token = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                    expiresAtMs = 2_000,
                ),
            response =
                PairingConfirmResponse(
                    kind = PairingDocumentKinds.CONFIRM_RESPONSE,
                    version = 1,
                    deviceId = PEER_ID,
                    displayName = "DESKTOP-WIN",
                    platform = "windows",
                    pairSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                    trustEpoch = 1,
                ),
            pairSecret = ByteArray(32) { 1 },
            nowMs = 1_000,
        )
        return store
    }

    private fun backoffWithoutJitter() = ReconnectBackoff(initialDelayMs = 1_000, maxDelayMs = 60_000, jitterRatio = 0.0)

    @Test
    fun `stays NotPaired and never dials without a saved peer`() =
        runTest {
            val connector = ScriptedConnector { throw IOException("unused") }
            val supervisor =
                SyncSupervisor(
                    pairing = PairingStore(FakeKeyValueStore(), FakeSecretProtector()),
                    repository = InMemorySyncRepository("22222222-2222-4222-8222-222222222222"),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                )
            backgroundScope.launch { supervisor.run() }
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(SyncConnectionState.NotPaired, supervisor.state.value)
            assertTrue(connector.calls.isEmpty())
        }

    @Test
    fun `unreachable hosts are tried in preference order with growing backoff`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { throw IOException("refused") }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(listOf(HOST_A, HOST_B), connector.calls)
            assertEquals(SyncConnectionState.WaitingRetry(0, 1_000), supervisor.state.value)

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(listOf(HOST_A, HOST_B, HOST_A, HOST_B), connector.calls)
            assertEquals(SyncConnectionState.WaitingRetry(1, 2_000), supervisor.state.value)

            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(SyncConnectionState.WaitingRetry(2, 4_000), supervisor.state.value)
        }

    @Test
    fun `a certificate pin mismatch never fails over to the next host`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { host -> throw PinMismatchException(host) }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(listOf(HOST_A), connector.calls)
            assertTrue(supervisor.state.value is SyncConnectionState.WaitingRetry)
        }

    @Test
    fun `rapid connect then disconnect cycles keep growing the backoff`() =
        runTest {
            // Weak-network flap: the dial succeeds, then the socket dies before authentication.
            // The unauthenticated session must not reset the schedule, or a flapping link would
            // turn the reconnect loop into a tight dial loop.
            val pairing = pairedStore()
            val connector = ScriptedConnector { InstantDropTransport() }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(listOf(HOST_A), connector.calls)
            assertEquals(SyncConnectionState.WaitingRetry(0, 1_000), supervisor.state.value)

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(listOf(HOST_A, HOST_A), connector.calls)
            assertEquals(SyncConnectionState.WaitingRetry(1, 2_000), supervisor.state.value)

            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(listOf(HOST_A, HOST_A, HOST_A), connector.calls)
            assertEquals(SyncConnectionState.WaitingRetry(2, 4_000), supervisor.state.value)
        }

    @Test
    fun `a network nudge dials immediately without resetting the schedule`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { throw IOException("refused") }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(listOf(HOST_A, HOST_B), connector.calls)
            assertEquals(SyncConnectionState.WaitingRetry(0, 1_000), supervisor.state.value)

            // Network came back: the wait is cut short with no virtual time passing at all...
            supervisor.nudgeReconnect()
            runCurrent()
            assertEquals(listOf(HOST_A, HOST_B, HOST_A, HOST_B), connector.calls)
            // ...but the failed dial still advanced the schedule instead of restarting it.
            assertEquals(SyncConnectionState.WaitingRetry(1, 2_000), supervisor.state.value)

            supervisor.nudgeReconnect()
            runCurrent()
            assertEquals(6, connector.calls.size)
            assertEquals(SyncConnectionState.WaitingRetry(2, 4_000), supervisor.state.value)

            // The plain timer path still works after nudges.
            advanceTimeBy(4_001)
            runCurrent()
            assertEquals(8, connector.calls.size)
            assertEquals(SyncConnectionState.WaitingRetry(3, 8_000), supervisor.state.value)
        }

    @Test
    fun `a nudge from before the wait began is drained rather than banked`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { throw IOException("refused") }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                )
            backgroundScope.launch { supervisor.run() }
            // The network event fires before the loop reaches its first wait: the dial that is
            // about to fail already sees that network, so the stale nudge must not skip the wait.
            supervisor.nudgeReconnect()

            runCurrent()
            assertEquals(listOf(HOST_A, HOST_B), connector.calls)
            assertEquals(SyncConnectionState.WaitingRetry(0, 1_000), supervisor.state.value)

            advanceTimeBy(999)
            runCurrent()
            assertEquals(listOf(HOST_A, HOST_B), connector.calls)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(HOST_A, HOST_B, HOST_A, HOST_B), connector.calls)
        }

    @Test
    fun `a nudge while unpaired never dials`() =
        runTest {
            val connector = ScriptedConnector { throw IOException("unused") }
            val supervisor =
                SyncSupervisor(
                    pairing = PairingStore(FakeKeyValueStore(), FakeSecretProtector()),
                    repository = InMemorySyncRepository("22222222-2222-4222-8222-222222222222"),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            supervisor.nudgeReconnect()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(SyncConnectionState.NotPaired, supervisor.state.value)
            assertTrue(connector.calls.isEmpty())
        }

    /**
     * Speaks just enough of the listener side to script one outcome per session: either the
     * Windows AuthThrottle answer (retryable RATE_LIMITED before auth, then close), or a
     * successful handshake (challenge -> accept any proof -> known_vector, then close).
     */
    private class ScriptedListenerTransport(
        private val throttle: Boolean,
        private val localDeviceId: String,
        /** When false the session stays open after authentication until disposed. */
        private val closeAfterAuth: Boolean = true,
        /** The wire version this fake listener accepted the dial on. */
        private val version: Int = 1,
    ) : SyncTransport {
        private val frames = Channel<TransportFrame>(Channel.UNLIMITED)

        var disposed = false
            private set

        override suspend fun receive(): TransportFrame = frames.receive()

        override suspend fun send(text: String) {
            when (SyncWire.decode(text, version).type) {
                SyncMessageTypes.HELLO ->
                    if (throttle) {
                        deliver(
                            SyncMessageTypes.ERROR,
                            ErrorBody(code = SyncErrorCodes.RATE_LIMITED, retryable = true, retryAfterMs = 30_000),
                        )
                        frames.trySend(TransportFrame.Closed)
                    } else {
                        deliver(
                            SyncMessageTypes.CHALLENGE,
                            ChallengeBody(
                                algorithm = HMAC_ALGORITHM,
                                nonce = Base64Url.encode(ByteArray(32) { (it * 3).toByte() }),
                                challengerDeviceId = PEER_ID,
                                responderDeviceId = localDeviceId,
                                trustEpoch = 1,
                                expiresAtMs = Long.MAX_VALUE,
                            ),
                        )
                    }
                SyncMessageTypes.AUTH -> {
                    // A data message is the dialer's confirmation that auth passed.
                    deliver(SyncMessageTypes.KNOWN_VECTOR, SyncStateBody(origins = emptyList()))
                    if (closeAfterAuth) {
                        frames.trySend(TransportFrame.Closed)
                    }
                }
                else -> {}
            }
        }

        private fun deliver(
            type: String,
            body: Any,
        ) {
            frames.trySend(TransportFrame.Text(SyncWire.encode(type, SyncWire.newRequestId(), body, version)))
        }

        override suspend fun close(
            code: Int,
            reason: String,
        ) {
            frames.trySend(TransportFrame.Closed)
        }

        override fun dispose() {
            disposed = true
            frames.trySend(TransportFrame.Closed)
        }
    }

    @Test
    fun `peer rate limiting is announced once per episode and re-arms after recovery`() =
        runTest {
            val pairing = pairedStore()
            // Session script: throttled, throttled (same episode), authenticated, throttled again.
            val scripts = ArrayDeque(listOf(true, true, false, true))
            val connector =
                ScriptedConnector {
                    ScriptedListenerTransport(scripts.removeFirst(), pairing.localDeviceId())
                }
            var announcements = 0
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    onAuthThrottled = { announcements++ },
                )
            backgroundScope.launch { supervisor.run() }

            // First throttled session announces the lockout to the user.
            runCurrent()
            assertEquals(1, announcements)

            // The backoff retry inside the same lockout window stays silent.
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(1, announcements)

            // An authenticated session ends the episode...
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(1, announcements)

            // ...so the next lockout is a fresh episode and announces again.
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(2, announcements)
            assertTrue(scripts.isEmpty())
        }

    /** A transport whose session lives until the test (or the supervisor) disposes it. */
    private class HeldOpenTransport : SyncTransport {
        private val frames = Channel<TransportFrame>(Channel.UNLIMITED)
        var disposed = false
            private set

        override suspend fun receive(): TransportFrame = frames.receive()

        override suspend fun send(text: String) = Unit

        override suspend fun close(
            code: Int,
            reason: String,
        ) {
            frames.trySend(TransportFrame.Closed)
        }

        override fun dispose() {
            disposed = true
            frames.trySend(TransportFrame.Closed)
        }
    }

    private class ScriptedBluetoothDialer(
        var behavior: suspend () -> SyncTransport?,
    ) : BluetoothFallbackDialer {
        var dials = 0
            private set

        override suspend fun dial(
            localDeviceId: String,
            peer: com.clipsync.android.pairing.PairedPeer,
            pairSecret: ByteArray,
        ): SyncTransport? {
            dials++
            return behavior()
        }
    }

    @Test
    fun `bluetooth is dialed only after every ip host failed and marks the state`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { throw IOException("unreachable") }
            val bluetoothTransport = HeldOpenTransport()
            val dialer = ScriptedBluetoothDialer { bluetoothTransport }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    bluetoothDialer = dialer,
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            // Both IP hosts were tried first; only then the one Bluetooth dial followed.
            assertEquals(listOf(HOST_A, HOST_B), connector.calls)
            assertEquals(1, dialer.dials)
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.BLUETOOTH),
                supervisor.state.value,
            )
        }

    @Test
    fun `a certificate pin mismatch never falls back to bluetooth`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { host -> throw PinMismatchException(host) }
            val dialer = ScriptedBluetoothDialer { HeldOpenTransport() }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    bluetoothDialer = dialer,
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            // Trust failed, not connectivity: Bluetooth must never mask a wrong certificate.
            assertEquals(0, dialer.dials)
        }

    @Test
    fun `a failed bluetooth dial lands in the same backoff as an unreachable host`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { throw IOException("unreachable") }
            val dialer = ScriptedBluetoothDialer { null }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    bluetoothDialer = dialer,
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(1, dialer.dials)
            assertEquals(SyncConnectionState.WaitingRetry(0, 1_000), supervisor.state.value)

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(2, dialer.dials)
            assertEquals(SyncConnectionState.WaitingRetry(1, 2_000), supervisor.state.value)
        }

    @Test
    fun `an ip session never dials bluetooth`() =
        runTest {
            val pairing = pairedStore()
            val connector = ScriptedConnector { HeldOpenTransport() }
            val dialer = ScriptedBluetoothDialer { HeldOpenTransport() }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    bluetoothDialer = dialer,
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.IP),
                supervisor.state.value,
            )
            assertEquals(0, dialer.dials)
        }

    @Test
    fun `a live bluetooth session switches back once the ip probe connects`() =
        runTest {
            val pairing = pairedStore()
            var ipReachable = false
            val ipTransport = HeldOpenTransport()
            val connector =
                ScriptedConnector {
                    if (ipReachable) ipTransport else throw IOException("unreachable")
                }
            // The Bluetooth session must authenticate: virtual time will pass the 15 s handshake
            // watchdog, which kills an unauthenticated session and would mask the switchback.
            val bluetoothTransport =
                ScriptedListenerTransport(
                    throttle = false,
                    localDeviceId = pairing.localDeviceId(),
                    closeAfterAuth = false,
                )
            val dialer = ScriptedBluetoothDialer { bluetoothTransport }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    bluetoothDialer = dialer,
                    ipProbeIntervalMs = 30_000,
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.BLUETOOTH),
                supervisor.state.value,
            )

            // First probe tick: IP still down, the Bluetooth session stays.
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.BLUETOOTH),
                supervisor.state.value,
            )
            assertTrue(!bluetoothTransport.disposed)

            // IP comes back; the next probe tick wins a socket and the loop switches over.
            ipReachable = true
            advanceTimeBy(30_001)
            runCurrent()
            assertTrue(bluetoothTransport.disposed)
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.IP),
                supervisor.state.value,
            )
            // One Bluetooth dial total: the switchback reused the probe's socket, no re-dial.
            assertEquals(1, dialer.dials)
        }

    @Test
    fun `restartSession bounces a live session so the next dial honors the image toggle`() =
        runTest {
            val pairing = pairedStore()
            var imageSync = false
            lateinit var connector: ScriptedConnector
            connector =
                ScriptedConnector {
                    ScriptedListenerTransport(
                        throttle = false,
                        localDeviceId = pairing.localDeviceId(),
                        closeAfterAuth = false,
                        // The connector records the requested version before dialing: the fake
                        // listener answers on whichever version this dial asked for.
                        version = connector.versions.last(),
                    )
                }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    imageSyncEnabled = { imageSync },
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            // Image sync was off at dial time, so the session runs on text-only v1.
            assertEquals(listOf(1), connector.versions)
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.IP),
                supervisor.state.value,
            )

            // Flipping the preference alone changes nothing: the wire version was fixed at
            // dial time and a healthy session has no reason to drop on its own.
            imageSync = true
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(1), connector.versions)

            // The restart closes the live session; that close counts as authenticated, so the
            // backoff resets and the redial one initial-delay later dials v2 first.
            supervisor.restartSession()
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(listOf(1, 2), connector.versions)
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.IP),
                supervisor.state.value,
            )
        }

    @Test
    fun `restartSession that lands mid-dial still bounces the fresh session`() =
        runTest {
            val pairing = pairedStore()
            var imageSync = false
            // Each dial suspends until the test releases it, so the toggle can land while
            // the dial is in flight — the window where activeEngine is still null.
            val dialGate = Channel<Unit>()
            val versions = mutableListOf<Int>()
            val connector =
                object : SyncConnector {
                    override suspend fun connect(
                        host: String,
                        port: Int,
                        certSha256: String,
                        protocolVersion: Int,
                    ): SyncTransport {
                        versions.add(protocolVersion)
                        dialGate.receive()
                        return ScriptedListenerTransport(
                            throttle = false,
                            localDeviceId = pairing.localDeviceId(),
                            closeAfterAuth = false,
                            version = protocolVersion,
                        )
                    }
                }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    imageSyncEnabled = { imageSync },
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            // The dial is in flight on v1 when the user flips 图片同步: there is no live
            // session to close yet, so the request must survive until the session starts.
            assertEquals(listOf(1), versions)
            imageSync = true
            supervisor.restartSession()
            dialGate.send(Unit)
            runCurrent()

            // The stale-version session was bounced immediately; the redial one backoff
            // step later re-reads the preference and dials v2.
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(listOf(1, 2), versions)
            dialGate.send(Unit)
            runCurrent()
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.IP),
                supervisor.state.value,
            )
        }

    @Test
    fun `restartSession during a backoff wait cuts it short like a nudge`() =
        runTest {
            val pairing = pairedStore()
            var imageSync = false
            val connector = ScriptedConnector { throw IOException("refused") }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    imageSyncEnabled = { imageSync },
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(listOf(1, 1), connector.versions)
            assertEquals(SyncConnectionState.WaitingRetry(0, 1_000), supervisor.state.value)

            // No session to close while waiting: the restart still applies the new preference
            // immediately by cutting the wait short, and the fresh dials lead with v2.
            imageSync = true
            supervisor.restartSession()
            runCurrent()
            assertEquals(listOf(1, 1, 2, 1, 2, 1), connector.versions)
        }

    @Test
    fun `a reconnect nudge makes the bluetooth session probe ip immediately`() =
        runTest {
            val pairing = pairedStore()
            var ipReachable = false
            val connector =
                ScriptedConnector {
                    if (ipReachable) HeldOpenTransport() else throw IOException("unreachable")
                }
            val dialer = ScriptedBluetoothDialer { HeldOpenTransport() }
            val supervisor =
                SyncSupervisor(
                    pairing = pairing,
                    repository = InMemorySyncRepository(pairing.localDeviceId()),
                    connector = connector,
                    clientVersion = "0.1.0",
                    backoff = backoffWithoutJitter(),
                    bluetoothDialer = dialer,
                    ipProbeIntervalMs = 300_000,
                )
            backgroundScope.launch { supervisor.run() }

            runCurrent()
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.BLUETOOTH),
                supervisor.state.value,
            )

            // The network-available callback fires: no need to wait out the probe interval.
            ipReachable = true
            supervisor.nudgeReconnect()
            runCurrent()
            assertEquals(
                SyncConnectionState.Connected("DESKTOP-WIN", SyncTransportKind.IP),
                supervisor.state.value,
            )
        }
}
