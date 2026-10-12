package com.clipsync.android.sync

import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.protocol.ProtocolJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

private const val REVIEW_LOCAL_ID = "22222222-2222-4222-8222-222222222222"
private const val REVIEW_PEER_ID = "11111111-1111-4111-8111-111111111111"
private const val REVIEW_NOW = 1_776_000_000_000L

@OptIn(ExperimentalCoroutinesApi::class)
class SyncFetchBackpressureTest {
    @Test
    fun pausedFetchIsServedAfterResumeWithoutAnotherRequest() =
        runTest {
            var allowed = false
            val repository = InMemorySyncRepository(REVIEW_LOCAL_ID)
            val local = requireNotNull(repository.recordLocalClip("queued before pause", null, REVIEW_NOW))
            val transport = ReviewTransport()
            val engine = SyncEngine(repository, reviewConfig().copy(outboundAllowed = { allowed }), ByteArray(32))
            val result = CompletableDeferred<SyncSessionResult>()
            backgroundScope.launch { result.complete(engine.run(transport)) }
            transport.handshake()
            runCurrent()
            transport.deliver(SyncMessageTypes.CLIP_FETCH, ClipFetchBody(listOf(local.eventId)))
            runCurrent()
            val error = transport.sent.single { it.type == SyncMessageTypes.ERROR }.body as ErrorBody
            assertEquals(SyncErrorCodes.PAYLOAD_NOT_FOUND, error.code)
            assertTrue(error.retryable)
            advanceTimeBy(250)
            runCurrent()
            assertFalse(transport.sent.any { it.type == SyncMessageTypes.CLIP_PAYLOAD })
            allowed = true
            advanceTimeBy(100)
            runCurrent()
            val payload = transport.sent.single { it.type == SyncMessageTypes.CLIP_PAYLOAD }.body as ClipPayloadBody
            assertEquals(local.eventId, payload.clips.single().eventId)
            assertEquals("queued before pause", payload.clips.single().content)
            assertFalse(result.isCompleted)
        }

    @Test
    fun queuedImageSendShouldKeepConsumingPongsWhileWaitingForBackpressure() =
        runTest {
            val root = createTempDirectory("pr6-media-review").toFile()
            try {
                val blobStore = MediaBlobStore(root)
                val fixtureRoot = requireNotNull(System.getProperty("protocol.v2.fixtures.dir"))
                val sample = File(fixtureRoot, "media/png-8x8.png").readBytes()
                val blob = blobStore.commitBytes(sample)
                val memory = InMemorySyncRepository(REVIEW_LOCAL_ID)
                val image = memory.injectLocalImageEventForTest(blob.contentHash, sample.size, REVIEW_NOW)
                val repository =
                    object : SyncRepository by memory {
                        override val media: MediaBlobStore get() = blobStore
                    }
                val transport = ReviewTransport(ProtocolJson.PROTOCOL_V2)
                var allowed = true
                val engine =
                    SyncEngine(
                        repository,
                        reviewConfig().copy(
                            protocolVersion = ProtocolJson.PROTOCOL_V2,
                            pingIntervalMs = 50,
                            maxMissedPings = 3,
                            outboundAllowed = { allowed },
                        ),
                        ByteArray(32),
                    )
                val result = CompletableDeferred<SyncSessionResult>()
                val running = backgroundScope.launch { result.complete(engine.run(transport)) }
                transport.handshake()
                runCurrent()
                transport.queued = 5L * 1024 * 1024
                transport.deliver(SyncMessageTypes.CLIP_FETCH, ClipFetchBody(listOf(image.eventId)))
                runCurrent()
                assertTrue(transport.sent.any { it.type == SyncMessageTypes.CLIP_PAYLOAD_BEGIN })
                allowed = false
                backgroundScope.launch {
                    kotlinx.coroutines.delay(300)
                    transport.queued = 0
                }
                advanceTimeBy(250)
                runCurrent()
                val completion = if (result.isCompleted) result.getCompleted().detail else "still-running"
                assertFalse(
                    "A peer answering every ping must stay connected during backpressure: $completion",
                    result.isCompleted,
                )
                advanceTimeBy(100)
                runCurrent()
                assertFalse(transport.sent.any { it.type == SyncMessageTypes.CLIP_PAYLOAD_CHUNK })
                allowed = true
                advanceTimeBy(100)
                runCurrent()
                assertTrue(transport.sent.any { it.type == SyncMessageTypes.CLIP_PAYLOAD_END })
                running.cancelAndJoin()
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun pausedQueueCanHoldFetchBatchesForAFullOutbox() =
        runTest {
            val transport = ReviewTransport()
            val engine =
                SyncEngine(
                    InMemorySyncRepository(REVIEW_LOCAL_ID),
                    reviewConfig().copy(outboundAllowed = { false }),
                    ByteArray(32),
                )
            val result = CompletableDeferred<SyncSessionResult>()
            backgroundScope.launch { result.complete(engine.run(transport)) }
            transport.handshake()
            runCurrent()
            val eventIds = List(SyncLimits.MAX_FETCH_EVENT_IDS) { SyncWire.newRequestId() }
            repeat((10_000 + SyncLimits.MAX_FETCH_EVENT_IDS - 1) / SyncLimits.MAX_FETCH_EVENT_IDS) {
                transport.deliver(SyncMessageTypes.CLIP_FETCH, ClipFetchBody(eventIds))
                runCurrent()
            }
            transport.deliver(SyncMessageTypes.PING, PingBody(sentAtMs = REVIEW_NOW))
            runCurrent()
            assertTrue(transport.sent.any { it.type == SyncMessageTypes.PONG })
            assertFalse(result.isCompleted)
        }

    @Test
    fun fetchQueueIsBoundedWhilePaused() =
        runTest {
            val transport = ReviewTransport()
            val engine =
                SyncEngine(
                    InMemorySyncRepository(REVIEW_LOCAL_ID),
                    reviewConfig().copy(outboundAllowed = { false }),
                    ByteArray(32),
                )
            val result = CompletableDeferred<SyncSessionResult>()
            backgroundScope.launch { result.complete(engine.run(transport)) }
            transport.handshake()
            runCurrent()
            repeat(SyncEngine.MAX_PENDING_FETCH_BATCHES + 2) {
                transport.deliver(
                    SyncMessageTypes.CLIP_FETCH,
                    ClipFetchBody(listOf("66666666-6666-4666-8666-666666666666")),
                )
                runCurrent()
            }
            assertEquals(SyncErrorCodes.RATE_LIMITED, result.await().errorCode)
        }

    private fun reviewConfig() =
        SyncSessionConfig(
            localDeviceId = REVIEW_LOCAL_ID,
            peerDeviceId = REVIEW_PEER_ID,
            trustEpoch = 3,
            clientVersion = "0.1.0",
            nowMs = { REVIEW_NOW },
        )

    private class ReviewTransport(
        val version: Int = ProtocolJson.PROTOCOL_V1,
    ) : SyncTransport {
        private val incoming = Channel<TransportFrame>(Channel.UNLIMITED)
        val sent = mutableListOf<SyncMessage>()
        var queued = 0L
        override val queuedBytes: Long get() = queued

        override suspend fun receive(): TransportFrame = incoming.receive()

        override suspend fun send(text: String) {
            val message = SyncWire.decode(text, version)
            sent.add(message)
            if (message.type == SyncMessageTypes.PING) {
                val ping = message.body as PingBody
                deliver(SyncMessageTypes.PONG, PongBody(pingSentAtMs = ping.sentAtMs, sentAtMs = REVIEW_NOW))
            }
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
            incoming.trySend(TransportFrame.Text(SyncWire.encode(type, SyncWire.newRequestId(), body, version)))
        }

        fun handshake() {
            deliver(
                SyncMessageTypes.CHALLENGE,
                ChallengeBody(
                    algorithm = HMAC_ALGORITHM,
                    nonce = Base64Url.encode(ByteArray(32)),
                    challengerDeviceId = REVIEW_PEER_ID,
                    responderDeviceId = REVIEW_LOCAL_ID,
                    trustEpoch = 3,
                    expiresAtMs = REVIEW_NOW + 30_000,
                ),
            )
            deliver(SyncMessageTypes.KNOWN_VECTOR, SyncStateBody(emptyList()))
        }
    }
}
