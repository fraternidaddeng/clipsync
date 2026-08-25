package com.clipsync.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.platform.clipboard.SharedClipboardWrites
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets

private const val ANDROID_ID = "22222222-2222-4222-8222-222222222222"
private const val WINDOWS_ID = "11111111-1111-4111-8111-111111111111"
private const val EPOCH = 5L
private val SECRET = ByteArray(32) { (it + 11).toByte() }
private const val AWAIT_TIMEOUT_MS = 10_000L

/**
 * Integration-style JVM test of the full Android sync chain with no real device and no real
 * network: a scripted transport plays the Windows listener while the production pieces run
 * for real — [SyncEngine] over the Room-backed [RoomSyncRepository], committed remote clips
 * flowing out through [InboxDelivery] (inbox record first, then optional auto-apply through a
 * fake clipboard writer), and local captures travelling [ClipboardCaptureManager] →
 * [SettingsGatedClipOutbox] → Room outbox → the engine's periodic announce drain.
 *
 * The wiring mirrors [ClipboardSyncService.launchSyncStack]: the same gate expressions, the
 * same newest-only auto-apply rule per committed batch, and the same share-outbox drain.
 */
@RunWith(RobolectricTestRunner::class)
class WindowsAndroidSyncChainTest {
    private lateinit var context: Context
    private lateinit var database: ClipSyncDatabase
    private lateinit var store: ClipSyncRepository
    private lateinit var repository: RoomSyncRepository
    private lateinit var settings: SyncSettingsStore
    private lateinit var inbox: ClipInbox
    private lateinit var captureManager: ClipboardCaptureManager

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakeWriter = FakeClipboardWriter()
    private val deliveries = mutableListOf<Pair<String, Boolean>>()
    private var nudges = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SharedClipboardWrites.reset()

        database =
            Room
                .inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = ClipSyncRepository(database, ANDROID_ID)
        repository =
            RoomSyncRepository(
                store = store,
                fanOutPeerIds = { listOf(WINDOWS_ID) },
                maxContentUtf8Bytes = { settings.effectiveMaxSyncTextBytes },
            )

        settings =
            SyncSettingsStore(
                SharedPrefsKeyValueStore(context, name = SyncSettingsStore.PREFERENCES_NAME),
            )

        // Same shape as ClipboardSyncService: the pause/private gates wrap the queue itself,
        // and the inbox resolves notification copy actions straight from the Room store.
        val servicesStore = SharedPrefsKeyValueStore(context, name = "clipsync.sync")
        inbox = RoomClipInbox { store }
        SyncServices.install(
            outbox =
                SettingsGatedClipOutbox(
                    KeyValueClipOutbox(servicesStore, maxUtf8Bytes = { settings.effectiveMaxSyncTextBytes }),
                    settings,
                ),
            inbox = inbox,
            syncRequester = { nudges++ },
        )

        captureManager =
            ClipboardCaptureManager(
                settings = settings,
                writeCoordinator = SharedClipboardWrites.coordinator(context),
            )

        InboxDelivery.writerFactory = { fakeWriter }
    }

    @After
    fun tearDown() {
        // cancel() alone is fire-and-forget: the engine invokes onRemoteClipsCommitted inline
        // on Dispatchers.Default, and sendAcks runs before raiseCommitted, so a test that only
        // awaited the ack can reach tearDown while InboxDelivery.deliver is still executing.
        // A leaked deliver would steal per-window budget from the JVM-global
        // InboxDelivery.notificationGate installed by a later test (seen on CI:
        // InboxDeliveryTest.imageArrivalCardsShareTheTextFloodGate counted 1 card, not 2).
        // Joining guarantees nothing from this class survives into the next one.
        runBlocking { engineScope.coroutineContext.job.cancelAndJoin() }
        InboxDelivery.writerFactory = InboxDelivery.defaultWriterFactory
        database.close()
    }

    @Test
    fun windowsPushLandsInRoomAndInboxAndAutoAppliesThroughTheWriter() =
        runBlocking {
            val transport = startEngine()
            transport.completeHandshake()

            val eventId = "33333333-3333-4333-8333-333333333333"
            val content = "pushed from the windows pc"
            transport.pushClipFromWindows(eventId, content, originSeq = 1)

            // The engine acknowledged exactly the received sequence.
            val acks = transport.awaitSent(SyncMessageTypes.ACK_RANGES).body as AckRangesBody
            assertEquals(listOf(OriginRangesDto(WINDOWS_ID, listOf(RangeDto(1, 1)))), acks.acks)

            // Room committed the event and advanced the receive vector.
            val entry = store.searchHistory().single()
            assertEquals(content, entry.content)
            assertEquals(WINDOWS_ID, entry.originDeviceId)
            assertEquals(1L, store.knownVector().getValue(WINDOWS_ID).contiguousSeq)

            // Inbox record always happens first; auto-apply then reached the fake writer.
            awaitUntil("delivery to complete") {
                synchronized(deliveries) { deliveries.toList() } == listOf(eventId to true)
            }
            assertEquals(content, inbox.textFor(eventId))
            val write = fakeWriter.writes.single()
            assertEquals(content, write.text)
            assertEquals(eventId, write.originEventId)

            transport.peerCloses()
        }

    @Test
    fun onlyTheNewestClipOfABatchAutoAppliesButAllLandInTheInbox() =
        runBlocking {
            val transport = startEngine()
            transport.completeHandshake()

            val older = "44444444-4444-4444-8444-444444444444"
            val newer = "55555555-5555-4555-8555-555555555555"
            transport.deliver(
                SyncMessageTypes.CLIP_ANNOUNCE,
                ClipAnnounceBody(
                    listOf(
                        availableHeader(older, "first body", originSeq = 1),
                        availableHeader(newer, "second body", originSeq = 2),
                    ),
                ),
            )
            val fetch = transport.awaitSent(SyncMessageTypes.CLIP_FETCH).body as ClipFetchBody
            assertEquals(listOf(older, newer), fetch.eventIds)
            transport.deliver(
                SyncMessageTypes.CLIP_PAYLOAD,
                ClipPayloadBody(
                    listOf(
                        payloadItem(older, "first body", originSeq = 1),
                        payloadItem(newer, "second body", originSeq = 2),
                    ),
                ),
            )
            transport.awaitSent(SyncMessageTypes.ACK_RANGES)

            awaitUntil("both deliveries to complete") {
                synchronized(deliveries) { deliveries.toList() } == listOf(older to false, newer to true)
            }
            assertEquals("first body", inbox.textFor(older))
            assertEquals("second body", inbox.textFor(newer))
            // Windows behaves the same way: only the newest body reaches the system clipboard.
            val write = fakeWriter.writes.single()
            assertEquals("second body", write.text)

            transport.peerCloses()
        }

    @Test
    fun autoApplyOffKeepsThePushInTheInboxAndOffTheClipboard() =
        runBlocking {
            settings.autoApplyRemote = false
            val transport = startEngine()
            transport.completeHandshake()

            val eventId = "66666666-6666-4666-8666-666666666666"
            transport.pushClipFromWindows(eventId, "manual copy only", originSeq = 1)
            transport.awaitSent(SyncMessageTypes.ACK_RANGES)

            awaitUntil("delivery to complete") {
                synchronized(deliveries) { deliveries.toList() } == listOf(eventId to false)
            }
            assertEquals("manual copy only", inbox.textFor(eventId))
            assertTrue(fakeWriter.writes.isEmpty())

            transport.peerCloses()
        }

    @Test
    fun pausedSyncStillReceivesIntoTheInboxButNeverAutoApplies() =
        runBlocking {
            settings.syncPaused = true
            val transport = startEngine()
            transport.completeHandshake()

            val eventId = "77777777-7777-4777-8777-777777777777"
            transport.pushClipFromWindows(eventId, "received while paused", originSeq = 1)
            transport.awaitSent(SyncMessageTypes.ACK_RANGES)

            awaitUntil("inbox record") { inbox.textFor(eventId) == "received while paused" }
            assertTrue(fakeWriter.writes.isEmpty())
            assertEquals("received while paused", store.searchHistory().single().content)

            transport.peerCloses()
        }

    @Test
    fun phoneCopyTravelsThroughCaptureGateRoomAndTheDrainToWindows() =
        runBlocking {
            val transport = startEngine()
            transport.completeHandshake()

            // 1. A foreground copy enters through the capture manager and the settings gate.
            val outcome = captureManager.onClipboardChanged(change("copied on the phone"))
            assertEquals(CaptureOutcome.CAPTURED, outcome)
            assertEquals(1, nudges)

            // 2. The service-side drain moves the entry into Room, which allocates the sequence.
            drainShareOutbox()
            assertTrue(SyncServices.outbox.pending().isEmpty())

            // 3. The engine's outbox loop announces it to the Windows listener.
            val announce = transport.awaitSent(SyncMessageTypes.CLIP_ANNOUNCE).body as ClipAnnounceBody
            val header = announce.clips.single()
            assertEquals(ANDROID_ID, header.originDeviceId)
            assertEquals(1L, header.originSeq)
            assertEquals(ClipAvailability.AVAILABLE, header.availability)
            assertNull(header.reason)

            // 4. Windows pulls the body and receives the exact content.
            transport.deliver(SyncMessageTypes.CLIP_FETCH, ClipFetchBody(listOf(header.eventId)))
            val payload = transport.awaitSent(SyncMessageTypes.CLIP_PAYLOAD).body as ClipPayloadBody
            assertEquals("copied on the phone", payload.clips.single().content)

            // 5. The Windows ack clears the Room outbox: nothing is announced twice.
            transport.deliver(
                SyncMessageTypes.ACK_RANGES,
                AckRangesBody(listOf(OriginRangesDto(ANDROID_ID, listOf(RangeDto(1, 1))))),
            )
            awaitUntil("outbox drained") { store.pendingOutboxCount(WINDOWS_ID) == 0 }

            transport.peerCloses()
        }

    @Test
    fun pauseAndPrivateModeCloseTheOutboundGateAtEveryLayer() =
        runBlocking {
            val transport = startEngine()
            transport.completeHandshake()

            // Private mode: the capture manager refuses before the outbox is even consulted.
            settings.privateMode = true
            assertEquals(
                CaptureOutcome.SKIPPED_PRIVATE_MODE,
                captureManager.onClipboardChanged(change("secret")),
            )
            // Entry points that bypass capture (share sheet, tile) hit the settings-gated outbox.
            assertEquals(EnqueueResult.PrivateMode, SyncServices.outbox.enqueue("secret", ClipSource.SHARE_SHEET))

            settings.privateMode = false
            settings.syncPaused = true
            assertEquals(
                CaptureOutcome.SKIPPED_SYNC_PAUSED,
                captureManager.onClipboardChanged(change("held")),
            )
            assertEquals(EnqueueResult.SyncPaused, SyncServices.outbox.enqueue("held", ClipSource.QUICK_TILE))
            assertTrue(SyncServices.outbox.pending().isEmpty())
            assertEquals(0, nudges)

            // A clip already inside Room stays unannounced while the engine gate is closed.
            repository.recordLocalClip("landed before pause", sourceApp = null, nowMs = System.currentTimeMillis())
            transport.deliver(SyncMessageTypes.PING, PingBody(sentAtMs = 1))
            transport.awaitSent(SyncMessageTypes.PONG) // fence: no announce slipped out before this

            // Resuming lets the very next drain tick announce the held entry; nothing was lost.
            settings.syncPaused = false
            val announce = transport.awaitSent(SyncMessageTypes.CLIP_ANNOUNCE).body as ClipAnnounceBody
            assertEquals("landed before pause".length.toLong(), announce.clips.single().utf8Bytes)

            transport.peerCloses()
        }

    @Test
    fun autoAppliedWindowsClipIsNotEchoedBackToWindows() =
        runBlocking {
            // Use the production writer path: the shared write coordinator marks the auto-applied
            // content so the capture pipeline recognizes it as a self-write.
            InboxDelivery.writerFactory = InboxDelivery.defaultWriterFactory

            val transport = startEngine()
            transport.completeHandshake()

            val eventId = "88888888-8888-4888-8888-888888888888"
            val content = "auto applied windows body"
            transport.pushClipFromWindows(eventId, content, originSeq = 1)
            transport.awaitSent(SyncMessageTypes.ACK_RANGES)
            awaitUntil("auto-apply recorded") {
                synchronized(deliveries) { deliveries.toList() } == listOf(eventId to true)
            }

            // The OS clipboard-changed callback for our own write must not re-capture the clip.
            assertEquals(CaptureOutcome.SKIPPED_OWN_WRITE, captureManager.onClipboardChanged(change(content)))
            assertTrue(SyncServices.outbox.pending().isEmpty())
            assertEquals(0, nudges)

            // A genuine later copy of the same text still syncs: suppression is consume-once.
            assertEquals(CaptureOutcome.CAPTURED, captureManager.onClipboardChanged(change(content)))
            assertEquals(1, SyncServices.outbox.pending().size)

            transport.peerCloses()
        }

    // ---- wiring helpers -------------------------------------------------------------------

    /**
     * Launches a [SyncEngine] against the Room repository with the same gate wiring as
     * [ClipboardSyncService]: outbound announces obey pause/private, and committed remote
     * clips flow through [InboxDelivery] with the newest-only auto-apply rule.
     */
    private fun startEngine(): FakeWindowsTransport {
        val engine =
            SyncEngine(
                repository = repository,
                config =
                    SyncSessionConfig(
                        localDeviceId = ANDROID_ID,
                        peerDeviceId = WINDOWS_ID,
                        trustEpoch = EPOCH,
                        clientVersion = "0.1.0",
                        outboxDrainIntervalMs = 25,
                        pingIntervalMs = 60_000,
                        outboundAllowed = { !settings.syncPaused && !settings.privateMode },
                    ),
                pairSecret = SECRET,
                onRemoteClipsCommitted = { committed ->
                    val autoApply = InboxDelivery.autoApplyAllowed(settings)
                    val newestEventId = committed.lastOrNull()?.eventId
                    committed.forEach { applied ->
                        val delivered =
                            InboxDelivery.deliver(
                                context,
                                applied.eventId,
                                applied.content,
                                autoApply = autoApply && applied.eventId == newestEventId,
                            )
                        synchronized(deliveries) { deliveries.add(applied.eventId to delivered) }
                    }
                },
            )
        val transport = FakeWindowsTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        engineScope.launch { result.complete(engine.run(transport)) }
        return transport
    }

    /** Mirrors ClipboardSyncService.drainShareOutbox: queue entries land in the Room store. */
    private suspend fun drainShareOutbox() {
        for (entry in SyncServices.outbox.pending()) {
            repository.recordLocalClip(
                text = entry.text,
                sourceApp = "android.app",
                nowMs = entry.createdAtEpochMillis,
            )
            SyncServices.outbox.remove(entry.eventId)
        }
    }

    private fun change(text: String) = ClipboardChange(text, Sha256ContentHasher.hash(text), System.currentTimeMillis())

    private suspend fun awaitUntil(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            delay(25)
        }
        fail("timed out waiting for $what")
    }

    // ---- scripted Windows listener --------------------------------------------------------

    private fun availableHeader(
        eventId: String,
        content: String,
        originSeq: Long,
    ) = ClipHeaderDto(
        eventId = eventId,
        originDeviceId = WINDOWS_ID,
        originSeq = originSeq,
        availability = ClipAvailability.AVAILABLE,
        kind = "text",
        contentHash = Sha256ContentHasher.hash(content),
        utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
        sourceApp = "notepad.exe",
        createdAtMs = System.currentTimeMillis(),
    )

    private fun payloadItem(
        eventId: String,
        content: String,
        originSeq: Long,
    ) = ClipPayloadItemDto(
        eventId = eventId,
        originDeviceId = WINDOWS_ID,
        originSeq = originSeq,
        kind = "text",
        content = content,
        contentHash = Sha256ContentHasher.hash(content),
        utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
        sourceApp = "notepad.exe",
        createdAtMs = System.currentTimeMillis(),
    )

    /** Scripts the listener half of one announce → fetch → payload exchange. */
    private suspend fun FakeWindowsTransport.pushClipFromWindows(
        eventId: String,
        content: String,
        originSeq: Long,
    ) {
        deliver(
            SyncMessageTypes.CLIP_ANNOUNCE,
            ClipAnnounceBody(listOf(availableHeader(eventId, content, originSeq))),
        )
        val fetch = awaitSent(SyncMessageTypes.CLIP_FETCH).body as ClipFetchBody
        assertEquals(listOf(eventId), fetch.eventIds)
        deliver(
            SyncMessageTypes.CLIP_PAYLOAD,
            ClipPayloadBody(listOf(payloadItem(eventId, content, originSeq))),
        )
    }

    /** Handshake as the Windows listener drives it: challenge, then a data message confirms. */
    private suspend fun FakeWindowsTransport.completeHandshake() {
        awaitSent(SyncMessageTypes.HELLO)
        deliver(
            SyncMessageTypes.CHALLENGE,
            ChallengeBody(
                algorithm = HMAC_ALGORITHM,
                nonce = Base64Url.encode(ByteArray(PairAuthProof.NONCE_LENGTH) { (it * 3).toByte() }),
                challengerDeviceId = WINDOWS_ID,
                responderDeviceId = ANDROID_ID,
                trustEpoch = EPOCH,
                expiresAtMs = System.currentTimeMillis() + 60_000,
            ),
        )
        awaitSent(SyncMessageTypes.AUTH)
        awaitSent(SyncMessageTypes.KNOWN_VECTOR)
        deliver(SyncMessageTypes.KNOWN_VECTOR, SyncStateBody(emptyList()))
    }

    /** In-memory transport the test scripts like the Windows listener would behave. */
    private class FakeWindowsTransport : SyncTransport {
        private val incoming = Channel<TransportFrame>(Channel.UNLIMITED)
        private val outgoing = Channel<String>(Channel.UNLIMITED)

        override suspend fun receive(): TransportFrame = incoming.receive()

        override suspend fun send(text: String) {
            outgoing.send(text)
        }

        override suspend fun close(
            code: Int,
            reason: String,
        ) {
            incoming.trySend(TransportFrame.Closed)
        }

        override fun dispose() {
            incoming.trySend(TransportFrame.Closed)
        }

        fun deliver(
            type: String,
            body: Any,
        ) {
            incoming.trySend(TransportFrame.Text(SyncWire.encode(type, SyncWire.newRequestId(), body)))
        }

        fun peerCloses() {
            incoming.trySend(TransportFrame.Closed)
        }

        /** Next sent message of [type]; heartbeat pings in between are skipped. */
        suspend fun awaitSent(type: String): SyncMessage =
            withTimeout(AWAIT_TIMEOUT_MS) {
                var message = SyncWire.decode(outgoing.receive())
                while (message.type == SyncMessageTypes.PING && type != SyncMessageTypes.PING) {
                    message = SyncWire.decode(outgoing.receive())
                }
                assertEquals(type, message.type)
                message
            }
    }
}
