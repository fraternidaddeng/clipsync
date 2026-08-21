package com.clipsync.android.sync

import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.protocol.AckRangesBody
import com.clipsync.android.protocol.AuthBody
import com.clipsync.android.protocol.ChallengeBody
import com.clipsync.android.protocol.ClipAnnounceBody
import com.clipsync.android.protocol.ClipAvailability
import com.clipsync.android.protocol.ClipFetchBody
import com.clipsync.android.protocol.ClipHeaderDto
import com.clipsync.android.protocol.ClipPayloadBody
import com.clipsync.android.protocol.ClipPayloadItemDto
import com.clipsync.android.protocol.ErrorBody
import com.clipsync.android.protocol.HelloBody
import com.clipsync.android.protocol.OriginRangesDto
import com.clipsync.android.protocol.OriginStateDto
import com.clipsync.android.protocol.PairAuthProof
import com.clipsync.android.protocol.ProtocolErrorCodes
import com.clipsync.android.protocol.ProtocolMessageTypes
import com.clipsync.android.protocol.RangeDto
import com.clipsync.android.protocol.SyncMessageWriter
import com.clipsync.android.protocol.SyncStateDto
import com.clipsync.android.protocol.WantRangesBody
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipPersistence
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.ClipSession
import com.clipsync.android.storage.InMemoryClipPersistence
import com.clipsync.android.storage.OUTBOX_PENDING
import com.clipsync.android.storage.RemoteClipEvent
import com.clipsync.android.storage.TerminalReasons
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import java.util.Base64
import java.util.UUID
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSessionEngineTest {
    private val hasher = Sha256ContentHasher
    private val logs = mutableListOf<String>()
    private val logger = SyncLogger { name, detail -> logs.add("$name $detail") }

    @Test
    fun `hello then challenge produces hmac-sha256 auth and authoritative known_vector`() = runTest {
        val repo = repository()
        val transport = FakeSyncTransport()
        val finished = launchEngine(repo, transport)

        val hello = transport.awaitSent().body as HelloBody
        assertEquals(LOCAL, hello.deviceId)
        assertEquals("android", hello.platform)
        assertEquals("0.1.0", hello.clientVersion)
        assertEquals(EPOCH, hello.trustEpoch)
        assertTrue(hello.knownVector.origins.isEmpty())

        val nonce = ByteArray(32) { it.toByte() }
        transport.peerSends(challenge(CHALLENGE_ID, nonce))

        val authMessage = transport.awaitSent()
        val auth = authMessage.body as AuthBody
        assertEquals(PairAuthProof.ALGORITHM, auth.algorithm)
        assertEquals("hmac-sha256", auth.algorithm)
        assertEquals(CHALLENGE_ID, auth.challengeRequestId)
        assertEquals(LOCAL, auth.responderDeviceId)
        assertEquals(EPOCH, auth.trustEpoch)
        val proof = Base64.getUrlDecoder().decode(auth.proof)
        assertTrue(
            PairAuthProof.verify(SECRET.copyOf(), CHALLENGE_ID, nonce, PEER, LOCAL, EPOCH, proof),
        )

        val vector = transport.awaitSent()
        assertEquals(ProtocolMessageTypes.KNOWN_VECTOR, vector.type)

        transport.peerSends(knownVector(contiguous = 0))
        transport.peerSendsFrame(TransportFrame.Closed)
        val result = finished.await()
        assertTrue(result.authenticated)
        assertFalse(logs.joinToString().contains(PairAuthProof.encodeBase64Url(nonce)))
        assertFalse(logs.joinToString().contains(auth.proof))
    }

    @Test
    fun `wrong trust epoch on challenge closes with TRUST_EPOCH_MISMATCH`() = runTest {
        val transport = FakeSyncTransport()
        val finished = launchEngine(repository(), transport)
        transport.awaitSent()
        transport.peerSends(challenge(CHALLENGE_ID, ByteArray(32) { 1 }, epoch = 99))

        val error = transport.awaitSent().body as ErrorBody
        assertEquals(ProtocolErrorCodes.TRUST_EPOCH_MISMATCH, error.code)
        assertFalse(error.retryable)
        val result = finished.await()
        assertFalse(result.authenticated)
        assertEquals(ProtocolErrorCodes.TRUST_EPOCH_MISMATCH, result.errorCode)
    }

    @Test
    fun `challenge identity mismatch closes with AUTH_FAILED`() = runTest {
        val transport = FakeSyncTransport()
        val finished = launchEngine(repository(), transport)
        transport.awaitSent()
        transport.peerSends(
            SyncMessageWriter.encode(
                ProtocolMessageTypes.CHALLENGE,
                CHALLENGE_ID,
                ChallengeBody(
                    algorithm = PairAuthProof.ALGORITHM,
                    nonce = PairAuthProof.encodeBase64Url(ByteArray(32) { 2 }),
                    challengerDeviceId = OTHER,
                    responderDeviceId = LOCAL,
                    trustEpoch = EPOCH,
                    expiresAtMs = NOW + 30_000,
                ),
            ),
        )
        val error = transport.awaitSent().body as ErrorBody
        assertEquals(ProtocolErrorCodes.AUTH_FAILED, error.code)
        assertEquals(ProtocolErrorCodes.AUTH_FAILED, finished.await().errorCode)
    }

    @Test
    fun `gap cursor emits want_ranges then ack after payload commit`() = runTest {
        val repo = repository()
        for (seq in 1L..10L) {
            repo.ingestRemoteClip(remote("clip-$seq", seq), sourcePeerId = PEER)
        }
        val transport = FakeSyncTransport()
        val finished = launchEngine(repo, transport)
        completeAuth(transport)

        transport.peerSends(knownVector(contiguous = 12))
        val wants = transport.awaitSent().body as WantRangesBody
        val request = wants.requests.single()
        assertEquals(PEER, request.originDeviceId)
        assertEquals(11L, request.ranges.single().startSeq)
        assertEquals(12L, request.ranges.single().endSeq)

        val event11 = remote("clip-11", 11)
        val event12 = remote("clip-12", 12)
        transport.peerSends(announceAvailable(event11, event12))
        val fetch = transport.awaitSent().body as ClipFetchBody
        assertEquals(setOf(event11.eventId, event12.eventId), fetch.eventIds.toSet())

        transport.peerSends(payload(event11, event12))
        val ack = transport.awaitSent().body as AckRangesBody
        assertEquals(PEER, ack.acks.single().originDeviceId)
        assertEquals(11L, ack.acks.single().ranges.single().startSeq)
        assertEquals(12L, ack.acks.single().ranges.single().endSeq)
        assertEquals(12L, repo.knownVector().origins.getValue(PEER).contiguousSeq)

        transport.peerSendsFrame(TransportFrame.Closed)
        assertTrue(finished.await().authenticated)
    }

    @Test
    fun `matching local hash skips clip_fetch and still acks`() = runTest {
        val repo = repository()
        repo.captureLocalText("shared-body", nowMs = NOW)
        val hash = hasher.hash("shared-body")
        val transport = FakeSyncTransport()
        val finished = launchEngine(repo, transport)
        completeAuth(transport)

        val event = RemoteClipEvent(
            eventId = UUID.randomUUID().toString(),
            originDeviceId = PEER,
            originSeq = 1,
            content = "shared-body",
            contentHash = hash,
            sourceApp = "app",
            createdAtMs = NOW,
        )
        transport.peerSends(knownVector(contiguous = 1))
        transport.awaitSent() // want_ranges 1..1
        transport.peerSends(announceAvailable(event))

        val next = transport.awaitSent()
        assertEquals(ProtocolMessageTypes.ACK_RANGES, next.type)
        assertEquals(null, transport.tryTakeSent())
        assertEquals("shared-body", repo.search("shared")[0].content)
        assertEquals(1L, repo.knownVector().origins.getValue(PEER).contiguousSeq)

        transport.peerSendsFrame(TransportFrame.Closed)
        finished.await()
        assertFalse(logs.joinToString().contains("shared-body"))
    }

    @Test
    fun `payload without a matching announce is MESSAGE_OUT_OF_ORDER and closes`() = runTest {
        val transport = FakeSyncTransport()
        val finished = launchEngine(repository(), transport)
        completeAuth(transport)
        transport.peerSends(knownVector(contiguous = 0))

        val orphan = remote("orphan", 1)
        transport.peerSends(payload(orphan))
        val error = transport.awaitSent().body as ErrorBody
        assertEquals(ProtocolErrorCodes.MESSAGE_OUT_OF_ORDER, error.code)
        assertEquals(ProtocolErrorCodes.MESSAGE_OUT_OF_ORDER, finished.await().errorCode)
    }

    @Test
    fun `new session resets announced outbox and drains pending announce`() = runTest {
        val repo = repository()
        val stored = repo.captureLocalText("outbox-item", nowMs = NOW, peerId = PEER) as CaptureResult.Stored
        repo.markAnnounced(repo.outboxPending(PEER).map { it.id })
        assertTrue(repo.outboxPending(PEER).isEmpty())

        val transport = FakeSyncTransport()
        val finished = launchEngine(repo, transport)
        completeAuth(transport)
        transport.peerSends(knownVector(contiguous = 0))

        val announce = transport.awaitSent().body as ClipAnnounceBody
        val header = announce.clips.single()
        assertEquals(stored.eventId, header.eventId)
        assertEquals(ClipAvailability.AVAILABLE, header.availability)
        assertEquals(stored.contentHash, header.contentHash)
        assertTrue(repo.outboxPending(PEER).isEmpty())

        transport.peerSendsFrame(TransportFrame.Closed)
        finished.await()
        assertFalse(logs.joinToString().contains("outbox-item"))
    }

    @Test
    fun `identity conflict on ingest closes with EVENT_CONFLICT`() = runTest {
        val repo = repository()
        val first = remote("original", 1)
        repo.ingestRemoteClip(first, sourcePeerId = PEER)
        val transport = FakeSyncTransport()
        val finished = launchEngine(repo, transport)
        completeAuth(transport)

        val conflicting = first.copy(originSeq = 2)
        transport.peerSends(knownVector(contiguous = 2))
        transport.awaitSent() // want 2..2 (we have 1)
        transport.peerSends(announceAvailable(conflicting))
        val error = transport.awaitSent().body as ErrorBody
        assertEquals(ProtocolErrorCodes.EVENT_CONFLICT, error.code)
        assertEquals(ProtocolErrorCodes.EVENT_CONFLICT, finished.await().errorCode)
    }

    @Test
    fun `unavailable announce ingests a terminal marker and acks without fetch`() = runTest {
        val repo = repository()
        val transport = FakeSyncTransport()
        val finished = launchEngine(repo, transport)
        completeAuth(transport)
        transport.peerSends(knownVector(contiguous = 1))
        transport.awaitSent() // want 1..1

        val eventId = UUID.randomUUID().toString()
        transport.peerSends(
            SyncMessageWriter.encode(
                ClipAnnounceBody(
                    listOf(
                        ClipHeaderDto(
                            eventId = eventId,
                            originDeviceId = PEER,
                            originSeq = 1,
                            availability = ClipAvailability.UNAVAILABLE,
                            reason = TerminalReasons.DELETED,
                        ),
                    ),
                ),
            ),
        )
        val ack = transport.awaitSent().body as AckRangesBody
        assertEquals(1L, ack.acks.single().ranges.single().endSeq)
        assertEquals(1L, repo.knownVector().origins.getValue(PEER).contiguousSeq)
        assertTrue(repo.search("").isEmpty())

        transport.peerSendsFrame(TransportFrame.Closed)
        finished.await()
    }

    @Test
    fun `session options default to a 30s application ping and three missed pongs`() {
        val options = SyncSessionOptions()
        assertEquals(30_000L, options.pingIntervalMs)
        assertEquals(3, options.maxMissedPings)
    }

    @Test
    fun `fatal AUTH_FAILED from the peer closes`() = runTest {
        val transport = FakeSyncTransport()
        val finished = launchEngine(repository(), transport)
        transport.awaitSent()
        transport.peerSends(
            SyncMessageWriter.encode(ErrorBody(code = ProtocolErrorCodes.AUTH_FAILED, retryable = false)),
        )
        val result = finished.await()
        assertFalse(result.authenticated)
        assertEquals(ProtocolErrorCodes.AUTH_FAILED, result.errorCode)
    }

    @Test
    fun `reconnect after a completed fetch does not push clip_payload without a new fetch`() = runTest {
        val repo = repository()
        val stored = repo.captureLocalText("session-one", nowMs = NOW, peerId = PEER) as CaptureResult.Stored

        val firstTransport = FakeSyncTransport()
        val first = launchEngine(repo, firstTransport)
        completeAuth(firstTransport)
        firstTransport.peerSends(knownVector(contiguous = 0))

        val firstAnnounce = firstTransport.awaitSent().body as ClipAnnounceBody
        assertEquals(stored.eventId, firstAnnounce.clips.single().eventId)
        firstTransport.peerSends(fetch(stored.eventId))
        val firstPayload = firstTransport.awaitSent()
        assertEquals(ProtocolMessageTypes.CLIP_PAYLOAD, firstPayload.type)
        assertEquals(stored.eventId, (firstPayload.body as ClipPayloadBody).clips.single().eventId)
        firstTransport.peerSends(ackLocal(stored.originSeq))
        firstTransport.peerSendsFrame(TransportFrame.Closed)
        val firstResult = first.await()
        assertTrue(firstResult.authenticated)
        assertEquals(null, firstResult.errorCode)
        assertTrue(repo.outboxPending(PEER).isEmpty())

        val secondTransport = FakeSyncTransport()
        val second = launchEngine(repo, secondTransport)
        completeAuth(secondTransport)
        secondTransport.peerSends(knownVectorCoveringLocal(stored.originSeq))
        runCurrent()

        val leaked = secondTransport.drainSent().filter { it.type == ProtocolMessageTypes.CLIP_PAYLOAD }
        assertTrue("reconnect must not push clip_payload unless the peer fetched", leaked.isEmpty())
        assertEquals(null, secondTransport.tryTakeSent())

        secondTransport.peerSendsFrame(TransportFrame.Closed)
        val secondResult = second.await()
        assertTrue(secondResult.authenticated)
        assertEquals(null, secondResult.errorCode)
        assertEquals("peer_closed", secondResult.detail)
    }

    @Test
    fun `reannounce after reset is acked without fetch and does not send clip_payload`() = runTest {
        val repo = repository()
        val stored = repo.captureLocalText("hash-dedup", nowMs = NOW, peerId = PEER) as CaptureResult.Stored
        repo.markAnnounced(repo.outboxPending(PEER).map { it.id })
        assertTrue(repo.outboxPending(PEER).isEmpty())

        val transport = FakeSyncTransport()
        val finished = launchEngine(repo, transport)
        completeAuth(transport)
        transport.peerSends(knownVector(contiguous = 0))

        val announce = transport.awaitSent().body as ClipAnnounceBody
        assertEquals(stored.eventId, announce.clips.single().eventId)
        transport.peerSends(ackLocal(stored.originSeq))
        runCurrent()

        val afterAck = transport.drainSent()
        assertTrue(
            "hash-dedup ack must not be answered with clip_payload",
            afterAck.none { it.type == ProtocolMessageTypes.CLIP_PAYLOAD },
        )
        assertTrue(repo.outboxPending(PEER).isEmpty())
        repo.resetOutboxToPending(PEER)
        assertTrue("ack must delete the outbox row, not leave it announced", repo.outboxPending(PEER).isEmpty())

        transport.peerSendsFrame(TransportFrame.Closed)
        val result = finished.await()
        assertTrue(result.authenticated)
        assertEquals(null, result.errorCode)
        assertFalse(logs.joinToString().contains("hash-dedup"))
    }

    @Test
    fun `resetOutboxToPending is visible as pending before the announce is marked`() = runTest {
        val repo = repository()
        repo.captureLocalText("pending-again", nowMs = NOW, peerId = PEER)
        repo.markAnnounced(repo.outboxPending(PEER).map { it.id })
        repo.resetOutboxToPending(PEER)
        val pending = repo.outboxPending(PEER)
        assertEquals(1, pending.size)
        assertEquals(OUTBOX_PENDING, pending.single().state)
    }

    @Test
    fun `a new local capture announces immediately without waiting for the drain timer`() = runTest {
        val repo = repository()
        val transport = FakeSyncTransport()
        val finished = launchEngine(
            repo,
            transport,
            // One day: if the announce below rode the timer, virtual time would jump.
            options = SyncSessionOptions(nowMs = { NOW }, outboxDrainIntervalMs = 86_400_000),
        )
        completeAuth(transport)
        transport.peerSends(knownVector(contiguous = 0))
        runCurrent()

        repo.captureLocalText("signal-driven announce", nowMs = NOW, peerId = PEER)
        val announce = transport.awaitSent().body as ClipAnnounceBody
        assertEquals(1, announce.clips.size)
        assertTrue(
            "announce must be signal-driven, not timer-driven (virtual time ${currentTime}ms)",
            currentTime < 60_000,
        )

        transport.peerSendsFrame(TransportFrame.Closed)
        val result = finished.await()
        assertTrue(result.authenticated)
    }

    @Test
    fun `a cancellation escaping the outbox loop fails the session instead of stalling`() = runTest {
        val inner = InMemoryClipPersistence()
        val armed = AtomicBoolean(false)
        val throwingPersistence = object : ClipPersistence by inner {
            override suspend fun <T> read(block: suspend ClipSession.() -> T): T {
                if (armed.getAndSet(false)) {
                    throw CancellationException("swallowed timeout-style cancellation")
                }
                return inner.read(block)
            }
        }
        val repo = ClipRepository(throwingPersistence, LOCAL, hasher)
        val transport = FakeSyncTransport()
        val engine = SyncSessionEngine(
            repository = repo,
            localDeviceId = LOCAL,
            peer = pairedPeer(),
            pairSecret = SECRET.copyOf(),
            options = SyncSessionOptions(nowMs = { NOW }, outboxDrainIntervalMs = 60_000),
            logger = logger,
        )
        // Mirror SyncController: requestClose() self-cancels the session job, so
        // run() ends in CancellationException that the controller maps to a retry.
        val ended = CompletableDeferred<String>()
        launch {
            val detail = try {
                engine.run(transport).detail
            } catch (_: CancellationException) {
                "session_closed"
            }
            ended.complete(detail)
        }
        completeAuth(transport)
        transport.peerSends(knownVector(contiguous = 0))
        runCurrent()

        armed.set(true)
        repo.captureLocalText("must not stall", nowMs = NOW, peerId = PEER)

        val detail = ended.await()
        assertTrue(
            "session must end (got: $detail), not stall with a dead outbox loop",
            detail == "send_failed" || detail == "session_closed",
        )
        assertTrue(
            "the dead loop must be visible in the log tags",
            logs.any { it.startsWith("background_loop_stopped") },
        )
    }

    private fun TestScope.launchEngine(
        repo: ClipRepository,
        transport: FakeSyncTransport,
        options: SyncSessionOptions = SyncSessionOptions(nowMs = { NOW }, outboxDrainIntervalMs = 60_000),
    ): CompletableDeferred<SyncSessionResult> {
        val engine = SyncSessionEngine(
            repository = repo,
            localDeviceId = LOCAL,
            peer = pairedPeer(),
            pairSecret = SECRET.copyOf(),
            options = options,
            logger = logger,
        )
        val done = CompletableDeferred<SyncSessionResult>()
        launch { done.complete(engine.run(transport)) }
        return done
    }

    private suspend fun completeAuth(transport: FakeSyncTransport) {
        transport.awaitSent() // hello
        transport.peerSends(challenge(CHALLENGE_ID, ByteArray(32) { 3 }))
        transport.awaitSent() // auth
        transport.awaitSent() // known_vector
    }

    private fun repository(): ClipRepository = ClipRepository(InMemoryClipPersistence(), LOCAL, hasher)

    private fun pairedPeer() = PairedPeer(
        deviceId = PEER,
        displayName = "DESKTOP-WIN",
        platform = "windows",
        certSha256 = "ab".repeat(32),
        trustEpoch = EPOCH,
        hosts = listOf("127.0.0.1"),
        port = 47654,
        pairedAtMs = NOW,
    )

    private fun challenge(requestId: String, nonce: ByteArray, epoch: Long = EPOCH): String =
        SyncMessageWriter.encode(
            ProtocolMessageTypes.CHALLENGE,
            requestId,
            ChallengeBody(
                algorithm = PairAuthProof.ALGORITHM,
                nonce = PairAuthProof.encodeBase64Url(nonce),
                challengerDeviceId = PEER,
                responderDeviceId = LOCAL,
                trustEpoch = epoch,
                expiresAtMs = NOW + 30_000,
            ),
        )

    private fun knownVector(contiguous: Long): String =
        SyncMessageWriter.encode(
            SyncStateDto(
                if (contiguous == 0L) {
                    emptyList()
                } else {
                    listOf(OriginStateDto(PEER, contiguous, receivedRanges = null))
                },
            ),
        )

    private fun knownVectorCoveringLocal(localSeq: Long): String =
        SyncMessageWriter.encode(
            SyncStateDto(listOf(OriginStateDto(LOCAL, localSeq, receivedRanges = null))),
        )

    private fun fetch(vararg eventIds: String): String =
        SyncMessageWriter.encode(ClipFetchBody(eventIds.toList()))

    private fun ackLocal(seq: Long): String =
        SyncMessageWriter.encode(
            AckRangesBody(listOf(OriginRangesDto(LOCAL, listOf(RangeDto(seq, seq))))),
        )

    private fun announceAvailable(vararg events: RemoteClipEvent): String =
        SyncMessageWriter.encode(
            ClipAnnounceBody(
                events.map { event ->
                    val text = requireNotNull(event.content) { "text announce requires content" }
                    ClipHeaderDto(
                        eventId = event.eventId,
                        originDeviceId = event.originDeviceId,
                        originSeq = event.originSeq,
                        availability = ClipAvailability.AVAILABLE,
                        kind = "text",
                        contentHash = event.contentHash,
                        utf8Bytes = text.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                        sourceApp = event.sourceApp,
                        createdAtMs = event.createdAtMs,
                        expiresAtMs = event.expiresAtMs,
                    )
                },
            ),
        )

    private fun payload(vararg events: RemoteClipEvent): String =
        SyncMessageWriter.encode(
            ClipPayloadBody(
                events.map { event ->
                    val text = requireNotNull(event.content) { "text payload requires content" }
                    ClipPayloadItemDto(
                        eventId = event.eventId,
                        originDeviceId = event.originDeviceId,
                        originSeq = event.originSeq,
                        kind = "text",
                        content = text,
                        contentHash = event.contentHash,
                        utf8Bytes = text.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                        sourceApp = event.sourceApp,
                        createdAtMs = event.createdAtMs,
                        expiresAtMs = event.expiresAtMs,
                    )
                },
            ),
        )

    private fun remote(content: String, seq: Long): RemoteClipEvent =
        RemoteClipEvent(
            eventId = UUID.randomUUID().toString(),
            originDeviceId = PEER,
            originSeq = seq,
            content = content,
            contentHash = hasher.hash(content),
            sourceApp = "app",
            createdAtMs = NOW,
        )

    companion object {
        private const val LOCAL = "22222222-2222-4222-8222-222222222222"
        private const val PEER = "11111111-1111-4111-8111-111111111111"
        private const val OTHER = "33333333-3333-4333-8333-333333333333"
        private const val CHALLENGE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private const val EPOCH = 1L
        private const val NOW = 1_700_000_000_000L
        private val SECRET = ByteArray(32) { 9 }
    }
}
