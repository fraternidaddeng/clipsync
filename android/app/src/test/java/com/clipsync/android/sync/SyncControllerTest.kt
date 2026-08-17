package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.InMemoryClipPersistence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerTest {
    @Test
    fun `start without a pairing stays idle unpaired`() = runTest {
        val controller = controller(store = PairingStore(FakeKeyValueStore(), FakeSecretProtector()))
        controller.start()
        runCurrent()
        assertEquals(SyncStatus.IDLE_UNPAIRED, controller.status().status)
        controller.stop()
        assertEquals(SyncStatus.STOPPED, controller.status().status)
    }

    @Test
    fun `certificate mismatch blocks and does not reconnect`() = runTest {
        val delays = mutableListOf<Long>()
        val controller = controller(
            connector = SyncConnector { _, _, _ -> SyncConnectResult.CertificateMismatch("127.0.0.1") },
            options = SyncSessionOptions(
                nowMs = { 0 },
                delayMs = { delays.add(it) },
            ),
        )
        controller.start()
        runCurrent()
        assertEquals(SyncStatus.CERTIFICATE_MISMATCH, controller.status().status)
        assertTrue(delays.isEmpty())
        controller.stop()
    }

    @Test
    fun `unreachable reconnects with 1 2 4 8 16 30s backoff`() = runTest {
        val delays = mutableListOf<Long>()
        val controller = controller(
            connector = SyncConnector { _, _, _ -> SyncConnectResult.Unreachable(listOf("127.0.0.1")) },
            options = SyncSessionOptions(
                nowMs = { 0 },
                delayMs = { ms ->
                    delays.add(ms)
                    kotlinx.coroutines.delay(ms)
                },
            ),
        )
        controller.start()
        advanceTimeBy(1_000 + 2_000 + 4_000 + 8_000 + 16_000 + 30_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays.take(6))
        assertEquals(SyncStatus.BACKING_OFF, controller.status().status)
        controller.stop()
        assertEquals(SyncStatus.STOPPED, controller.status().status)
    }

    @Test
    fun `successful dial reports ready then backs off after the peer closes`() = runTest {
        val transport = FakeSyncTransport()
        val opened = CompletableDeferred<Unit>()
        val controller = controller(
            connector = SyncConnector { _, _, _ ->
                opened.complete(Unit)
                SyncConnectResult.Connected(transport)
            },
            options = SyncSessionOptions(nowMs = { NOW }, outboxDrainIntervalMs = 60_000),
        )
        controller.start()
        opened.await()
        runCurrent()
        assertEquals(SyncStatus.AUTHENTICATING, controller.status().status)

        transport.awaitSent()
        transport.peerSends(
            SyncMessageWriterChallenge.helloPathChallenge(),
        )
        transport.awaitSent() // auth
        transport.awaitSent() // known_vector
        transport.peerSends(SyncMessageWriterChallenge.emptyVector())
        runCurrent()
        assertEquals(SyncStatus.READY, controller.status().status)
        assertTrue(controller.status().authenticated)

        transport.peerSendsFrame(TransportFrame.Closed)
        testScheduler.runCurrent()
        assertEquals(SyncStatus.BACKING_OFF, controller.status().status)
        controller.stop()
    }

    @Test
    fun `backoff helper is 1 2 4 8 16 30 then stays at 30 under the five minute cap`() {
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        assertEquals(expected, (0..6).map { SyncController.reconnectBackoffMs(it) })
        assertTrue(SyncController.reconnectBackoffMs(100) <= SyncSessionOptions.MAX_BACKOFF_MS)
    }

    @Test
    fun `stop after ready does not open a second session`() = runTest {
        val connects = mutableListOf<FakeSyncTransport>()
        val controller = controller(
            connector = SyncConnector { _, _, _ ->
                val transport = FakeSyncTransport()
                connects += transport
                SyncConnectResult.Connected(transport)
            },
            options = SyncSessionOptions(
                nowMs = { NOW },
                outboxDrainIntervalMs = 60_000,
                delayMs = { kotlinx.coroutines.delay(it) },
            ),
        )
        controller.start()
        runCurrent()
        val transport = connects.single()
        transport.awaitSent()
        transport.peerSends(SyncMessageWriterChallenge.helloPathChallenge())
        transport.awaitSent()
        transport.awaitSent()
        transport.peerSends(SyncMessageWriterChallenge.emptyVector())
        runCurrent()
        assertEquals(SyncStatus.READY, controller.status().status)

        controller.stop()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, connects.size)
        assertEquals(SyncStatus.STOPPED, controller.status().status)
    }

    @Test
    fun `start is ignored while a loop is already running`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val controller = controller(
            connector = SyncConnector { _, _, _ ->
                hold.await()
                awaitCancellation()
            },
        )
        controller.start()
        runCurrent()
        assertEquals(SyncStatus.CONNECTING, controller.status().status)
        controller.start()
        hold.complete(Unit)
        controller.stop()
        assertEquals(SyncStatus.STOPPED, controller.status().status)
    }

    private fun TestScope.controller(
        store: PairingStore = pairedStore(),
        connector: SyncConnector = SyncConnector { _, _, _ -> SyncConnectResult.Unreachable(listOf("127.0.0.1")) },
        options: SyncSessionOptions = SyncSessionOptions(),
    ): SyncController = SyncController(
        pairingStore = store,
        repository = ClipRepository(InMemoryClipPersistence(), LOCAL),
        connector = connector,
        scope = this,
        options = options,
    )

    private fun pairedStore(): PairingStore {
        val keys = FakeKeyValueStore()
        keys.write(mapOf("local.device_id" to LOCAL))
        val store = PairingStore(keys, FakeSecretProtector())
        store.savePeer(
            PairingQrPayload(
                kind = PairingDocumentKinds.QR,
                version = 1,
                hosts = listOf("127.0.0.1"),
                port = 47654,
                deviceId = PEER,
                displayName = "DESKTOP-WIN",
                certSha256 = "ab".repeat(32),
                token = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                expiresAtMs = NOW,
            ),
            PairingConfirmResponse(
                kind = PairingDocumentKinds.CONFIRM_RESPONSE,
                version = 1,
                deviceId = PEER,
                displayName = "DESKTOP-WIN",
                platform = "windows",
                pairSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                trustEpoch = 1,
            ),
            ByteArray(32) { 9 },
            nowMs = NOW,
        )
        return store
    }

    companion object {
        private const val LOCAL = "22222222-2222-4222-8222-222222222222"
        private const val PEER = "11111111-1111-4111-8111-111111111111"
        private const val NOW = 1_700_000_000_000L
    }
}

/** Challenge/vector frames for the controller ready-path test. */
private object SyncMessageWriterChallenge {
    private const val LOCAL = "22222222-2222-4222-8222-222222222222"
    private const val PEER = "11111111-1111-4111-8111-111111111111"
    private const val CHALLENGE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private const val NOW = 1_700_000_000_000L

    fun helloPathChallenge(): String = com.clipsync.android.protocol.SyncMessageWriter.encode(
        com.clipsync.android.protocol.ProtocolMessageTypes.CHALLENGE,
        CHALLENGE_ID,
        com.clipsync.android.protocol.ChallengeBody(
            algorithm = com.clipsync.android.protocol.PairAuthProof.ALGORITHM,
            nonce = com.clipsync.android.protocol.PairAuthProof.encodeBase64Url(ByteArray(32) { 3 }),
            challengerDeviceId = PEER,
            responderDeviceId = LOCAL,
            trustEpoch = 1,
            expiresAtMs = NOW + 30_000,
        ),
    )

    fun emptyVector(): String = com.clipsync.android.protocol.SyncMessageWriter.encode(
        com.clipsync.android.protocol.SyncStateDto(emptyList()),
    )
}
