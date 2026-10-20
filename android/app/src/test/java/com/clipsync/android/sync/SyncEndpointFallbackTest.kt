package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEndpointFallbackTest {
    private fun storedPeer(hosts: List<String>): PairingStore {
        val store = PairingStore(FakeKeyValueStore(), FakeSecretProtector())
        val peerId = "11111111-1111-4111-8111-111111111111"
        val secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        // Save directly to model an older/corrupt stored peer that bypassed the QR reader.
        store.savePeer(
            qr = PairingQrPayload(
                kind = PairingDocumentKinds.QR,
                version = 1,
                hosts = hosts,
                port = 47_654,
                deviceId = peerId,
                displayName = "DESKTOP-WIN",
                certSha256 = "ab".repeat(32),
                token = secret,
                expiresAtMs = 2_000,
            ),
            response = PairingConfirmResponse(
                kind = PairingDocumentKinds.CONFIRM_RESPONSE,
                version = 1,
                deviceId = peerId,
                displayName = "DESKTOP-WIN",
                platform = "windows",
                pairSecret = secret,
                trustEpoch = 1,
            ),
            pairSecret = ByteArray(32) { 1 },
            nowMs = 1_000,
        )
        return store
    }

    @Test
    fun `malformed stored hosts follow unreachable host fallback and keep retrying`() = runTest {
        val hosts = listOf("localhost/path", "[::1]:443", "2001:db8:::1")
        val pairing = storedPeer(hosts)
        val realConnector = OkHttpSyncConnector()
        val calls = mutableListOf<String>()
        var bluetoothDials = 0
        val supervisor = SyncSupervisor(
            pairing = pairing,
            repository = InMemorySyncRepository(pairing.localDeviceId()),
            connector = SyncConnector { host, port, pin, version ->
                calls += host
                realConnector.connect(host, port, pin, version)
            },
            clientVersion = "0.1.0",
            backoff = ReconnectBackoff(initialDelayMs = 1_000, jitterRatio = 0.0),
            bluetoothDialer = BluetoothFallbackDialer { _, _, _ ->
                bluetoothDials++
                null
            },
        )
        backgroundScope.launch { supervisor.run() }
        runCurrent()

        assertEquals(hosts, calls)
        assertEquals(1, bluetoothDials)
        assertEquals(SyncConnectionState.WaitingRetry(0, 1_000), supervisor.state.value)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(hosts + hosts, calls)
        assertEquals(2, bluetoothDials)
        assertEquals(SyncConnectionState.WaitingRetry(1, 2_000), supervisor.state.value)
    }
}
