package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

        override suspend fun connect(host: String, port: Int, certSha256: String): SyncTransport {
            calls.add(host)
            return behavior(host)
        }
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
}
