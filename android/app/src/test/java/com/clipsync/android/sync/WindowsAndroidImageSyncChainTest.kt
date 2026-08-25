package com.clipsync.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.media.ImageChunks
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.protocol.ProtocolJson
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory

private const val ANDROID_ID = "22222222-2222-4222-8222-222222222222"
private const val WINDOWS_ID = "11111111-1111-4111-8111-111111111111"
private const val EPOCH = 7L
private val SECRET = ByteArray(32) { (it + 23).toByte() }
private const val AWAIT_TIMEOUT_MS = 10_000L
private val EMPTY_VECTOR = SyncStateBody(emptyList())

/**
 * Session-level integration test of the Android image chunk state machine, closing the gap
 * documented in docs/verification-without-device.md: a scripted transport plays the Windows
 * v2 listener while the production pieces run for real — [SyncEngine] on protocol v2 over the
 * Room-backed [RoomSyncRepository] with a real content-addressed [MediaBlobStore] on disk.
 *
 * The images are the shared binary fixtures from protocol/v2/fixtures/media, so the bytes,
 * hashes, and the published chunk split are exactly the ones the Windows suite and the wire
 * fixtures already pin. Covered here: announce → want_ranges → clip_fetch →
 * clip_payload_begin/chunk/end → ack in both directions, hash-replay dedup without a second
 * transfer, and the v1 gate-off `local_only` terminal downgrade with its Room-persisted badge.
 */
@RunWith(RobolectricTestRunner::class)
class WindowsAndroidImageSyncChainTest {
    private lateinit var context: Context
    private lateinit var database: ClipSyncDatabase
    private lateinit var mediaRoot: File
    private lateinit var mediaStore: MediaBlobStore
    private lateinit var store: ClipSyncRepository
    private lateinit var repository: RoomSyncRepository

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val committed = mutableListOf<RemoteClipApplied>()

    private val fixturesRoot = File(requireNotNull(System.getProperty("protocol.v2.fixtures.dir")))
    private val mediaFixtures = File(fixturesRoot, "media")
    private val manifest =
        Json.parseToJsonElement(File(mediaFixtures, "manifest.json").readText()).jsonObject

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        mediaRoot = createTempDirectory("clipsync-image-chain").toFile()
        mediaStore = MediaBlobStore(mediaRoot)
        store = ClipSyncRepository(database, ANDROID_ID, mediaStore)
        repository = RoomSyncRepository(store = store, fanOutPeerIds = { listOf(WINDOWS_ID) })
    }

    @After
    fun tearDown() {
        engineScope.cancel()
        database.close()
        mediaRoot.deleteRecursively()
    }

    @Test
    fun windowsImageArrivesOverWantRangesFetchAndChunkedPayload() =
        runBlocking {
            val sample = png8x8()
            val transport = startEngine()
            // The listener's vector claims one unseen Windows-origin event.
            transport.completeHandshake(
                SyncStateBody(listOf(OriginStateDto(WINDOWS_ID, contiguousSeq = 1))),
            )

            // The engine wants exactly the missing range.
            val wants = transport.awaitSent(SyncMessageTypes.WANT_RANGES).body as WantRangesBody
            assertEquals(listOf(OriginRangesDto(WINDOWS_ID, listOf(RangeDto(1, 1)))), wants.requests)

            // Windows serves the want with an image announce; the engine fetches, and the body
            // arrives as begin → chunk → chunk → end using the manifest's published 41+42 split.
            val eventId = "33333333-3333-4333-8333-333333333333"
            transport.pushImageFromWindows(
                eventId,
                originSeq = 1,
                sample = sample,
                chunkSizes = listOf(manifestInt("png_8x8_chunk0_bytes"), manifestInt("png_8x8_chunk1_bytes")),
            )

            // The ack goes out only after the blob and the event row committed together.
            val acks = transport.awaitSent(SyncMessageTypes.ACK_RANGES).body as AckRangesBody
            assertEquals(listOf(OriginRangesDto(WINDOWS_ID, listOf(RangeDto(1, 1)))), acks.acks)
            assertImageCommitted(eventId, sample)

            // The committed callback carries what the service forwards to InboxDelivery.deliverImage.
            transport.fence()
            val applied = committedSnapshot().single()
            assertTrue(applied.isImage)
            assertEquals(eventId, applied.eventId)
            assertEquals(sample.contentHash, applied.contentHash)
            assertEquals(sample.mimeType, applied.mimeType)
            assertEquals("", applied.content)

            // A completed transfer leaves no half-written temp file behind.
            assertEquals(0, File(mediaRoot, MediaBlobStore.TEMP_DIRECTORY).listFiles().orEmpty().size)

            transport.peerCloses()
        }

    @Test
    fun phoneImageTravelsAnnounceFetchChunkStreamAndAckUntilTheOutboxClears() =
        runBlocking {
            val sample = jpeg1x1()
            val local = seedLocalImage(sample)
            val transport = startEngine()
            transport.completeHandshake()

            // The outbox drain announces the image with its full v2 blob metadata.
            val announce = transport.awaitSent(SyncMessageTypes.CLIP_ANNOUNCE).body as ClipAnnounceBody
            val header = announce.clips.single()
            assertEquals(local.eventId, header.eventId)
            assertEquals(ANDROID_ID, header.originDeviceId)
            assertEquals(ClipAvailability.AVAILABLE, header.availability)
            assertEquals(MediaLimits.KIND_IMAGE, header.kind)
            assertEquals(sample.contentHash, header.contentHash)
            assertEquals(sample.bytes.size.toLong(), header.encodedBytes)
            assertEquals(1L, header.pixelWidth)
            assertEquals(1L, header.pixelHeight)

            // Windows pulls the body; the engine streams begin → chunk* → end, byte-exact.
            transport.deliver(SyncMessageTypes.CLIP_FETCH, ClipFetchBody(listOf(local.eventId)))
            val streamed = transport.receiveImagePayload(local.eventId, sample)
            assertArrayEquals(sample.bytes, streamed)

            // The Windows ack clears the outbox obligation for good: even a session-start reset
            // (which revives announced-but-unacked rows) finds nothing to resurrect.
            transport.deliver(
                SyncMessageTypes.ACK_RANGES,
                AckRangesBody(listOf(OriginRangesDto(ANDROID_ID, listOf(RangeDto(1, 1))))),
            )
            transport.fence()
            store.resetOutboxToPending(WINDOWS_ID)
            assertEquals(0, store.pendingOutboxCount(WINDOWS_ID))

            transport.peerCloses()
        }

    @Test
    fun aReAnnouncedImageHashCommitsFromTheLocalBlobWithoutASecondTransfer() =
        runBlocking {
            val sample = png8x8()
            val transport = startEngine()
            transport.completeHandshake()

            val first = "44444444-4444-4444-8444-444444444444"
            transport.pushImageFromWindows(
                first,
                originSeq = 1,
                sample = sample,
                chunkSizes = listOf(sample.bytes.size),
            )
            transport.awaitSent(SyncMessageTypes.ACK_RANGES)

            // Same bytes under a new event id: the engine replays the stored blob (protocol v2
            // hash dedup) and acks straight away — awaitSent fails here if any clip_fetch goes out.
            val second = "55555555-5555-4555-8555-555555555555"
            transport.deliver(
                SyncMessageTypes.CLIP_ANNOUNCE,
                ClipAnnounceBody(listOf(imageHeader(second, originSeq = 2, sample = sample))),
            )
            val acks = transport.awaitSent(SyncMessageTypes.ACK_RANGES).body as AckRangesBody
            assertEquals(listOf(OriginRangesDto(WINDOWS_ID, listOf(RangeDto(2, 2)))), acks.acks)

            // Both event rows share the one content-addressed blob; the vector reached 2.
            assertEquals(2L, store.knownVector().getValue(WINDOWS_ID).contiguousSeq)
            assertEquals(sample.contentHash, requireNotNull(store.mediaRefFor(first)).contentHash)
            assertEquals(sample.contentHash, requireNotNull(store.mediaRefFor(second)).contentHash)
            transport.fence()
            assertEquals(listOf(first, second), committedSnapshot().map { it.eventId })

            transport.peerCloses()
        }

    @Test
    fun aV1SessionDowngradesTheLocalImageToLocalOnlyAndRoomKeepsTheBadge() =
        runBlocking {
            val sample = png8x8()
            val local = seedLocalImage(sample)
            val transport = startEngine(ProtocolJson.PROTOCOL_V1)
            transport.completeHandshake()

            // The image cannot travel v1: the drain announces a `local_only` terminal marker
            // instead, so the Windows cursor still advances past the sequence (ADR 0005 §4).
            val announce = transport.awaitSent(SyncMessageTypes.CLIP_ANNOUNCE).body as ClipAnnounceBody
            val header = announce.clips.single()
            assertEquals(local.eventId, header.eventId)
            assertEquals(ClipAvailability.UNAVAILABLE, header.availability)
            assertEquals("local_only", header.reason)
            assertNull(header.contentHash)

            // Room persists the 仅本机保留 badge; the row stays live and its bytes stay usable here.
            awaitUntil("local-only badge") { requireNotNull(store.getById(local.eventId)).isLocalOnly }
            assertFalse(requireNotNull(store.getById(local.eventId)).isDeleted)
            assertTrue(mediaStore.exists(sample.contentHash))

            // The listener acks the downgraded sequence and the outbox obligation clears for good.
            transport.deliver(
                SyncMessageTypes.ACK_RANGES,
                AckRangesBody(listOf(OriginRangesDto(ANDROID_ID, listOf(RangeDto(1, 1))))),
            )
            transport.fence()
            store.resetOutboxToPending(WINDOWS_ID)
            assertEquals(0, store.pendingOutboxCount(WINDOWS_ID))

            transport.peerCloses()
        }

    // ---- wiring helpers -------------------------------------------------------------------

    /** Launches a [SyncEngine] like [SyncSupervisor] does, collecting committed callbacks. */
    private fun startEngine(protocolVersion: Int = ProtocolJson.PROTOCOL_V2): FakeWindowsTransport {
        val engine =
            SyncEngine(
                repository = repository,
                config =
                    SyncSessionConfig(
                        localDeviceId = ANDROID_ID,
                        peerDeviceId = WINDOWS_ID,
                        trustEpoch = EPOCH,
                        clientVersion = "0.1.0",
                        protocolVersion = protocolVersion,
                        outboxDrainIntervalMs = 25,
                        pingIntervalMs = 60_000,
                    ),
                pairSecret = SECRET,
                onRemoteClipsCommitted = { batch -> synchronized(committed) { committed.addAll(batch) } },
            )
        val transport = FakeWindowsTransport(protocolVersion)
        val result = CompletableDeferred<SyncSessionResult>()
        engineScope.launch { result.complete(engine.run(transport)) }
        return transport
    }

    /** Commits fixture bytes into the blob store and records the local image event in Room. */
    private suspend fun seedLocalImage(sample: MediaSample): SyncableClipEvent {
        val validated = mediaStore.commitBytes(sample.bytes, sample.contentHash)
        return requireNotNull(
            repository.recordLocalImageClip(validated, sourceApp = "gallery.app", nowMs = System.currentTimeMillis()),
        )
    }

    /** Blob bytes, Room row, media metadata, and receive vector after one inbound transfer. */
    private suspend fun assertImageCommitted(
        eventId: String,
        sample: MediaSample,
    ) {
        assertArrayEquals(sample.bytes, mediaStore.readAllBytes(sample.contentHash))
        val entry = requireNotNull(store.getById(eventId))
        assertTrue(entry.isImage)
        assertEquals(sample.contentHash, entry.contentHash)
        assertEquals(WINDOWS_ID, entry.originDeviceId)
        val ref = requireNotNull(store.mediaRefFor(eventId))
        assertEquals(sample.mimeType, ref.mimeType)
        assertEquals(sample.bytes.size, ref.encodedBytes)
        assertEquals(sample.pixelWidth, ref.pixelWidth)
        assertEquals(sample.pixelHeight, ref.pixelHeight)
        assertEquals(1L, store.knownVector().getValue(WINDOWS_ID).contiguousSeq)
    }

    private fun committedSnapshot(): List<RemoteClipApplied> = synchronized(committed) { committed.toList() }

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

    // ---- shared media fixtures (protocol/v2/fixtures/media) --------------------------------

    private class MediaSample(
        val bytes: ByteArray,
        val contentHash: String,
        val mimeType: String,
        val pixelWidth: Int,
        val pixelHeight: Int,
    )

    private fun png8x8() =
        MediaSample(
            bytes = File(mediaFixtures, "png-8x8.png").readBytes(),
            contentHash = manifestString("png_8x8_sha256"),
            mimeType = MediaLimits.MIME_PNG,
            pixelWidth = 8,
            pixelHeight = 8,
        )

    private fun jpeg1x1() =
        MediaSample(
            bytes = File(mediaFixtures, "jpeg-1x1.jpg").readBytes(),
            contentHash = manifestString("jpeg_1x1_sha256"),
            mimeType = MediaLimits.MIME_JPEG,
            pixelWidth = 1,
            pixelHeight = 1,
        )

    private fun manifestString(key: String): String = requireNotNull(manifest[key]).jsonPrimitive.content

    private fun manifestInt(key: String): Int = requireNotNull(manifest[key]).jsonPrimitive.int

    // ---- scripted Windows listener ----------------------------------------------------------

    private fun imageHeader(
        eventId: String,
        originSeq: Long,
        sample: MediaSample,
    ) = ClipHeaderDto(
        eventId = eventId,
        originDeviceId = WINDOWS_ID,
        originSeq = originSeq,
        availability = ClipAvailability.AVAILABLE,
        kind = MediaLimits.KIND_IMAGE,
        contentHash = sample.contentHash,
        sourceApp = "snippingtool.exe",
        createdAtMs = System.currentTimeMillis(),
        mimeType = sample.mimeType,
        encodedBytes = sample.bytes.size.toLong(),
        pixelWidth = sample.pixelWidth.toLong(),
        pixelHeight = sample.pixelHeight.toLong(),
    )

    /** Scripts announce → fetch → begin → chunks → end for one Windows-origin image event. */
    private suspend fun FakeWindowsTransport.pushImageFromWindows(
        eventId: String,
        originSeq: Long,
        sample: MediaSample,
        chunkSizes: List<Int>,
    ) {
        deliver(
            SyncMessageTypes.CLIP_ANNOUNCE,
            ClipAnnounceBody(listOf(imageHeader(eventId, originSeq, sample))),
        )
        val fetch = awaitSent(SyncMessageTypes.CLIP_FETCH).body as ClipFetchBody
        assertEquals(listOf(eventId), fetch.eventIds)
        streamImageBody(eventId, sample, chunkSizes)
    }

    private fun FakeWindowsTransport.streamImageBody(
        eventId: String,
        sample: MediaSample,
        chunkSizes: List<Int>,
    ) {
        assertEquals(sample.bytes.size, chunkSizes.sum())
        val transferId = UUID.randomUUID().toString()
        deliver(
            SyncMessageTypes.CLIP_PAYLOAD_BEGIN,
            ClipPayloadBeginBody(
                transferId = transferId,
                eventId = eventId,
                chunkCount = chunkSizes.size,
                encodedBytes = sample.bytes.size.toLong(),
                contentHash = sample.contentHash,
                mimeType = sample.mimeType,
            ),
        )
        var offset = 0
        chunkSizes.forEachIndexed { index, size ->
            val slice = sample.bytes.copyOfRange(offset, offset + size)
            offset += size
            deliver(
                SyncMessageTypes.CLIP_PAYLOAD_CHUNK,
                ClipPayloadChunkBody(
                    transferId = transferId,
                    eventId = eventId,
                    chunkIndex = index,
                    chunkCount = chunkSizes.size,
                    chunkBytes = size,
                    data = ImageChunks.encodeBase64Url(slice),
                ),
            )
        }
        deliver(
            SyncMessageTypes.CLIP_PAYLOAD_END,
            ClipPayloadEndBody(transferId = transferId, eventId = eventId, contentHash = sample.contentHash),
        )
    }

    /** Consumes one outbound begin → chunk* → end stream and returns the reassembled bytes. */
    private suspend fun FakeWindowsTransport.receiveImagePayload(
        eventId: String,
        sample: MediaSample,
    ): ByteArray {
        val begin = awaitSent(SyncMessageTypes.CLIP_PAYLOAD_BEGIN).body as ClipPayloadBeginBody
        assertEquals(eventId, begin.eventId)
        assertEquals(sample.contentHash, begin.contentHash)
        assertEquals(sample.mimeType, begin.mimeType)
        assertEquals(sample.bytes.size.toLong(), begin.encodedBytes)
        val reassembly = ByteArrayOutputStream()
        repeat(begin.chunkCount) { index ->
            val chunk = awaitSent(SyncMessageTypes.CLIP_PAYLOAD_CHUNK).body as ClipPayloadChunkBody
            assertEquals(begin.transferId, chunk.transferId)
            assertEquals(index, chunk.chunkIndex)
            assertEquals(begin.chunkCount, chunk.chunkCount)
            reassembly.write(requireNotNull(ImageChunks.tryDecodeChunk(chunk.data, chunk.chunkBytes)))
        }
        val end = awaitSent(SyncMessageTypes.CLIP_PAYLOAD_END).body as ClipPayloadEndBody
        assertEquals(begin.transferId, end.transferId)
        assertEquals(sample.contentHash, end.contentHash)
        return reassembly.toByteArray()
    }

    /** Handshake as the Windows listener drives it; its vector is the last data message. */
    private suspend fun FakeWindowsTransport.completeHandshake(listenerVector: SyncStateBody = EMPTY_VECTOR) {
        val hello = awaitSent(SyncMessageTypes.HELLO).body as HelloBody
        if (version == ProtocolJson.PROTOCOL_V2) {
            assertEquals(listOf(ProtocolJson.CAPABILITY_IMAGE_CLIP_V2), hello.capabilities)
        } else {
            assertNull(hello.capabilities)
        }
        deliver(
            SyncMessageTypes.CHALLENGE,
            ChallengeBody(
                algorithm = HMAC_ALGORITHM,
                nonce = Base64Url.encode(ByteArray(PairAuthProof.NONCE_LENGTH) { (it * 5).toByte() }),
                challengerDeviceId = WINDOWS_ID,
                responderDeviceId = ANDROID_ID,
                trustEpoch = EPOCH,
                expiresAtMs = System.currentTimeMillis() + 60_000,
            ),
        )
        awaitSent(SyncMessageTypes.AUTH)
        awaitSent(SyncMessageTypes.KNOWN_VECTOR)
        deliver(SyncMessageTypes.KNOWN_VECTOR, listenerVector)
    }

    /** Ping/pong round-trip: everything delivered before it has been fully dispatched. */
    private suspend fun FakeWindowsTransport.fence() {
        deliver(SyncMessageTypes.PING, PingBody(sentAtMs = 1))
        awaitSent(SyncMessageTypes.PONG)
    }

    /** In-memory transport the tests script like the Windows v2 (or v1) listener would behave. */
    private class FakeWindowsTransport(
        val version: Int,
    ) : SyncTransport {
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
            incoming.trySend(
                TransportFrame.Text(SyncWire.encode(type, SyncWire.newRequestId(), body, version)),
            )
        }

        fun peerCloses() {
            incoming.trySend(TransportFrame.Closed)
        }

        /** Next sent message of [type]; heartbeat pings in between are skipped. */
        suspend fun awaitSent(type: String): SyncMessage =
            withTimeout(AWAIT_TIMEOUT_MS) {
                var message = SyncWire.decode(outgoing.receive(), version)
                while (message.type == SyncMessageTypes.PING && type != SyncMessageTypes.PING) {
                    message = SyncWire.decode(outgoing.receive(), version)
                }
                assertEquals(type, message.type)
                message
            }
    }
}
