package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        override suspend fun close(code: Int, reason: String) = Unit

        override fun dispose() = Unit
    }

    private fun pairedStore(): PairingStore {
        val store = PairingStore(FakeKeyValueStore(), FakeSecretProtector())
        store.savePeer(
            qr = PairingQrPayload(
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
            response = PairingConfirmResponse(
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
    fun `stays NotPaired and never dials without a saved peer`() = runTest {
        val connector = ScriptedConnector { throw IOException("unused") }
        val supervisor = SyncSupervisor(
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
    fun `unreachable hosts are tried in preference order with growing backoff`() = runTest {
        val pairing = pairedStore()
        val connector = ScriptedConnector { throw IOException("refused") }
        val supervisor = SyncSupervisor(
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
    fun `a certificate pin mismatch never fails over to the next host`() = runTest {
        val pairing = pairedStore()
        val connector = ScriptedConnector { host -> throw PinMismatchException(host) }
        val supervisor = SyncSupervisor(
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
    fun `rapid connect then disconnect cycles keep growing the backoff`() = runTest {
        // Weak-network flap: the dial succeeds, then the socket dies before authentication.
        // The unauthenticated session must not reset the schedule, or a flapping link would
        // turn the reconnect loop into a tight dial loop.
        val pairing = pairedStore()
        val connector = ScriptedConnector { InstantDropTransport() }
        val supervisor = SyncSupervisor(
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
    fun `a network nudge dials immediately without resetting the schedule`() = runTest {
        val pairing = pairedStore()
        val connector = ScriptedConnector { throw IOException("refused") }
        val supervisor = SyncSupervisor(
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
    fun `a nudge from before the wait began is drained rather than banked`() = runTest {
        val pairing = pairedStore()
        val connector = ScriptedConnector { throw IOException("refused") }
        val supervisor = SyncSupervisor(
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
    fun `a nudge while unpaired never dials`() = runTest {
        val connector = ScriptedConnector { throw IOException("unused") }
        val supervisor = SyncSupervisor(
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
    ) : SyncTransport {
        private val frames = Channel<TransportFrame>(Channel.UNLIMITED)

        override suspend fun receive(): TransportFrame = frames.receive()

        override suspend fun send(text: String) {
            when (SyncWire.decode(text).type) {
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
                    frames.trySend(TransportFrame.Closed)
                }
                else -> {}
            }
        }

        private fun deliver(type: String, body: Any) {
            frames.trySend(TransportFrame.Text(SyncWire.encode(type, SyncWire.newRequestId(), body)))
        }

        override suspend fun close(code: Int, reason: String) {
            frames.trySend(TransportFrame.Closed)
        }

        override fun dispose() {
            frames.trySend(TransportFrame.Closed)
        }
    }

    @Test
    fun `peer rate limiting is announced once per episode and re-arms after recovery`() = runTest {
        val pairing = pairedStore()
        // Session script: throttled, throttled (same episode), authenticated, throttled again.
        val scripts = ArrayDeque(listOf(true, true, false, true))
        val connector = ScriptedConnector {
            ScriptedListenerTransport(scripts.removeFirst(), pairing.localDeviceId())
        }
        var announcements = 0
        val supervisor = SyncSupervisor(
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
}
