package com.clipsync.android.sync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LOCAL_ID = "22222222-2222-4222-8222-222222222222"
private const val PEER_ID = "11111111-1111-4111-8111-111111111111"
private const val EPOCH = 3L
private val SECRET = ByteArray(32) { (it + 1).toByte() }

/** In-memory transport the test scripts like the Windows listener would behave. */
private class FakeTransport : SyncTransport {
    val incoming = Channel<TransportFrame>(Channel.UNLIMITED)
    val outgoing = Channel<String>(Channel.UNLIMITED)
    var closeCode: Int? = null
    var closeReason: String? = null

    override suspend fun receive(): TransportFrame = incoming.receive()

    override suspend fun send(text: String) {
        outgoing.send(text)
    }

    override suspend fun close(code: Int, reason: String) {
        if (closeCode == null) {
            closeCode = code
            closeReason = reason
        }
        incoming.trySend(TransportFrame.Closed)
    }

    override fun dispose() {
        incoming.trySend(TransportFrame.Closed)
    }

    fun deliver(type: String, body: Any, requestId: String = SyncWire.newRequestId()): String {
        incoming.trySend(TransportFrame.Text(SyncWire.encode(type, requestId, body)))
        return requestId
    }

    fun deliverRaw(frame: String) {
        incoming.trySend(TransportFrame.Text(frame))
    }

    fun peerCloses() {
        incoming.trySend(TransportFrame.Closed)
    }

    /** Next sent message of [type]; protocol pings in between are skipped. */
    suspend fun awaitSent(type: String): SyncMessage {
        while (true) {
            val message = SyncWire.decode(outgoing.receive())
            if (message.type == SyncMessageTypes.PING && type != SyncMessageTypes.PING) {
                continue
            }
            assertEquals(type, message.type)
            return message
        }
    }
}

private fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

class SyncEngineTest {
    private fun config(nowMs: () -> Long = { 1_776_000_000_000 }) = SyncSessionConfig(
        localDeviceId = LOCAL_ID,
        peerDeviceId = PEER_ID,
        trustEpoch = EPOCH,
        clientVersion = "0.1.0",
        nowMs = nowMs,
    )

    private fun challengeBody(
        nonce: ByteArray = ByteArray(32) { (it * 7).toByte() },
        epoch: Long = EPOCH,
        expiresAtMs: Long = 1_776_000_030_000,
    ) = ChallengeBody(
        algorithm = HMAC_ALGORITHM,
        nonce = Base64Url.encode(nonce),
        challengerDeviceId = PEER_ID,
        responderDeviceId = LOCAL_ID,
        trustEpoch = epoch,
        expiresAtMs = expiresAtMs,
    )

    @Test
    fun `full session - handshake, pull, push, and acks`() = runTest {
        val repository = InMemorySyncRepository(LOCAL_ID)
        val committed = mutableListOf<RemoteClipApplied>()
        val engine = SyncEngine(repository, config(), SECRET) { committed.addAll(it) }
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        // 1. Dialer opens with hello carrying identity, epoch, and the vector snapshot.
        val hello = transport.awaitSent(SyncMessageTypes.HELLO).body as HelloBody
        assertEquals(LOCAL_ID, hello.deviceId)
        assertEquals("android", hello.platform)
        assertEquals(EPOCH, hello.trustEpoch)
        assertTrue(hello.knownVector.origins.isEmpty())

        // 2. Listener challenges; the engine answers with the exact HMAC proof.
        val nonce = ByteArray(32) { (it * 7).toByte() }
        val challengeRequestId = transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody(nonce))
        val auth = transport.awaitSent(SyncMessageTypes.AUTH).body as AuthBody
        assertEquals(challengeRequestId, auth.challengeRequestId)
        assertEquals(
            Base64Url.encode(PairAuthProof.compute(SECRET, challengeRequestId, nonce, PEER_ID, LOCAL_ID, EPOCH)),
            auth.proof,
        )

        // 3. Post-auth the dialer publishes its authoritative known_vector.
        val vector = transport.awaitSent(SyncMessageTypes.KNOWN_VECTOR).body as SyncStateBody
        assertTrue(vector.origins.isEmpty())

        // 4. Listener's vector says it holds peer-origin 1..2; the engine wants both.
        transport.deliver(
            SyncMessageTypes.KNOWN_VECTOR,
            SyncStateBody(listOf(OriginStateDto(PEER_ID, contiguousSeq = 2))),
        )
        val wants = transport.awaitSent(SyncMessageTypes.WANT_RANGES).body as WantRangesBody
        assertEquals(listOf(OriginRangesDto(PEER_ID, listOf(RangeDto(1, 2)))), wants.requests)

        // 5. Announce: seq 1 has a body to fetch, seq 2 is a terminal marker.
        val eventId1 = "33333333-3333-4333-8333-333333333333"
        val eventId2 = "44444444-4444-4444-8444-444444444444"
        val content = "hello from windows"
        transport.deliver(
            SyncMessageTypes.CLIP_ANNOUNCE,
            ClipAnnounceBody(
                listOf(
                    ClipHeaderDto(
                        eventId = eventId1,
                        originDeviceId = PEER_ID,
                        originSeq = 1,
                        availability = ClipAvailability.AVAILABLE,
                        kind = "text",
                        contentHash = sha256Hex(content),
                        utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                        sourceApp = "notepad.exe",
                        createdAtMs = 1_776_000_000_000,
                    ),
                    ClipHeaderDto(
                        eventId = eventId2,
                        originDeviceId = PEER_ID,
                        originSeq = 2,
                        availability = ClipAvailability.UNAVAILABLE,
                        reason = "local_only",
                    ),
                ),
            ),
        )
        val fetch = transport.awaitSent(SyncMessageTypes.CLIP_FETCH).body as ClipFetchBody
        assertEquals(listOf(eventId1), fetch.eventIds)
        val terminalAck = transport.awaitSent(SyncMessageTypes.ACK_RANGES).body as AckRangesBody
        assertEquals(listOf(OriginRangesDto(PEER_ID, listOf(RangeDto(2, 2)))), terminalAck.acks)

        // 6. Payload commits, is acknowledged, and reaches the committed callback.
        transport.deliver(
            SyncMessageTypes.CLIP_PAYLOAD,
            ClipPayloadBody(
                listOf(
                    ClipPayloadItemDto(
                        eventId = eventId1,
                        originDeviceId = PEER_ID,
                        originSeq = 1,
                        kind = "text",
                        content = content,
                        contentHash = sha256Hex(content),
                        utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                        sourceApp = "notepad.exe",
                        createdAtMs = 1_776_000_000_000,
                    ),
                ),
            ),
        )
        val payloadAck = transport.awaitSent(SyncMessageTypes.ACK_RANGES).body as AckRangesBody
        assertEquals(listOf(OriginRangesDto(PEER_ID, listOf(RangeDto(1, 1)))), payloadAck.acks)
        assertEquals(listOf(content), committed.map { it.content })
        assertEquals(2, repository.knownVector().getValue(PEER_ID).contiguousSeq)

        // 7. A local clip is announced by the outbox drain (phone -> PC push).
        val local = repository.recordLocalClip("phone clip", sourceApp = null, nowMs = 1_776_000_001_000)!!
        val announce = transport.awaitSent(SyncMessageTypes.CLIP_ANNOUNCE).body as ClipAnnounceBody
        val header = announce.clips.single()
        assertEquals(local.eventId, header.eventId)
        assertEquals(LOCAL_ID, header.originDeviceId)
        assertEquals(ClipAvailability.AVAILABLE, header.availability)
        assertNull(header.reason)

        // 8. The listener fetches the body and acknowledges; the outbox empties.
        transport.deliver(SyncMessageTypes.CLIP_FETCH, ClipFetchBody(listOf(local.eventId)))
        val payload = transport.awaitSent(SyncMessageTypes.CLIP_PAYLOAD).body as ClipPayloadBody
        assertEquals("phone clip", payload.clips.single().content)
        transport.deliver(
            SyncMessageTypes.ACK_RANGES,
            AckRangesBody(listOf(OriginRangesDto(LOCAL_ID, listOf(RangeDto(1, 1))))),
        )
        transport.deliver(SyncMessageTypes.PING, PingBody(sentAtMs = 1)) // fence: ack processed
        transport.awaitSent(SyncMessageTypes.PONG)
        assertTrue(repository.getOutboxBatch(PEER_ID, 10).isEmpty())

        // 9. Peer closes; the session ends authenticated.
        transport.peerCloses()
        val sessionResult = result.await()
        assertTrue(sessionResult.authenticated)
        assertEquals("peer_closed", sessionResult.detail)
        assertNull(sessionResult.errorCode)
    }

    @Test
    fun `challenge with a wrong trust epoch fails the session`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody(epoch = EPOCH + 1))
        val error = transport.awaitSent(SyncMessageTypes.ERROR).body as ErrorBody
        assertEquals(SyncErrorCodes.TRUST_EPOCH_MISMATCH, error.code)
        assertFalse(error.retryable)

        val sessionResult = result.await()
        assertFalse(sessionResult.authenticated)
        assertEquals(SyncErrorCodes.TRUST_EPOCH_MISMATCH, sessionResult.errorCode)
        assertEquals(SyncCloseCodes.POLICY_VIOLATION, transport.closeCode)
    }

    @Test
    fun `an expired challenge fails the session`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody(expiresAtMs = 1_775_999_999_999))
        assertEquals(
            SyncErrorCodes.CHALLENGE_EXPIRED,
            (transport.awaitSent(SyncMessageTypes.ERROR).body as ErrorBody).code,
        )
        assertEquals(SyncErrorCodes.CHALLENGE_EXPIRED, result.await().errorCode)
    }

    @Test
    fun `data before authentication is rejected`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(SyncMessageTypes.KNOWN_VECTOR, SyncStateBody(emptyList()))
        assertEquals(
            SyncErrorCodes.AUTH_REQUIRED,
            (transport.awaitSent(SyncMessageTypes.ERROR).body as ErrorBody).code,
        )
        assertFalse(result.await().authenticated)
    }

    @Test
    fun `request id reuse with different content is replay detection`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        val requestId = transport.deliver(SyncMessageTypes.PING, PingBody(sentAtMs = 1))
        transport.awaitSent(SyncMessageTypes.PONG)
        transport.deliver(SyncMessageTypes.PING, PingBody(sentAtMs = 2), requestId)
        assertEquals(
            SyncErrorCodes.REPLAY_DETECTED,
            (transport.awaitSent(SyncMessageTypes.ERROR).body as ErrorBody).code,
        )
        assertEquals("request_id_reuse", result.await().detail)
    }

    @Test
    fun `an identical retry frame is ignored`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        val frame = SyncWire.encode(SyncMessageTypes.PING, SyncWire.newRequestId(), PingBody(sentAtMs = 9))
        transport.deliverRaw(frame)
        transport.awaitSent(SyncMessageTypes.PONG)
        transport.deliverRaw(frame)
        // The retry produced no second pong: the next outbound frame answers the challenge.
        transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody())
        transport.awaitSent(SyncMessageTypes.AUTH)
        transport.peerCloses()
        result.await()
    }

    @Test
    fun `a fatal peer error ends the session with its code`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(SyncMessageTypes.ERROR, ErrorBody(code = SyncErrorCodes.AUTH_FAILED, retryable = false))
        val sessionResult = result.await()
        assertFalse(sessionResult.authenticated)
        assertEquals(SyncErrorCodes.AUTH_FAILED, sessionResult.errorCode)
        assertEquals("peer_reported_fatal_error", sessionResult.detail)
    }

    @Test
    fun `a retryable peer error keeps the session alive`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(
            SyncMessageTypes.ERROR,
            ErrorBody(code = SyncErrorCodes.RATE_LIMITED, retryable = true, retryAfterMs = 1_000),
        )
        transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody())
        transport.awaitSent(SyncMessageTypes.AUTH)
        transport.peerCloses()
        result.await()
    }

    @Test
    fun `missing challenge times the handshake out`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        val sessionResult = result.await()
        assertFalse(sessionResult.authenticated)
        assertEquals("handshake_timeout", sessionResult.detail)
    }

    @Test
    fun `unanswered pings close the session`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody())
        transport.awaitSent(SyncMessageTypes.AUTH)
        transport.awaitSent(SyncMessageTypes.KNOWN_VECTOR)
        transport.deliver(SyncMessageTypes.KNOWN_VECTOR, SyncStateBody(emptyList()))

        // Never answer the heartbeat: after maxMissedPings the engine hangs up on its own.
        val sessionResult = result.await()
        assertTrue(sessionResult.authenticated)
        assertEquals("ping_timeout", sessionResult.detail)
    }

    @Test
    fun `binary frames are a schema violation`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.incoming.trySend(TransportFrame.Binary)
        assertEquals(SyncErrorCodes.SCHEMA_VIOLATION, result.await().errorCode)
    }

    @Test
    fun `oversized frames are payload too large`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.incoming.trySend(TransportFrame.TooLarge)
        assertEquals(SyncErrorCodes.PAYLOAD_TOO_LARGE, result.await().errorCode)
    }

    @Test
    fun `a revoked pairing kills the live session`() = runTest {
        var trusted = true
        val engine = SyncEngine(
            InMemorySyncRepository(LOCAL_ID),
            config().copy(peerStillTrusted = { trusted }),
            SECRET,
        )
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody())
        transport.awaitSent(SyncMessageTypes.AUTH)
        transport.awaitSent(SyncMessageTypes.KNOWN_VECTOR)

        trusted = false
        transport.deliver(SyncMessageTypes.KNOWN_VECTOR, SyncStateBody(emptyList()))
        assertEquals(SyncErrorCodes.DEVICE_REVOKED, result.await().errorCode)
    }

    @Test
    fun `inbound want_ranges above the cap is rate limited but not fatal`() = runTest {
        val engine = SyncEngine(InMemorySyncRepository(LOCAL_ID), config(), SECRET)
        val transport = FakeTransport()
        val result = CompletableDeferred<SyncSessionResult>()
        backgroundScope.launchEngine(engine, transport, result)

        transport.awaitSent(SyncMessageTypes.HELLO)
        transport.deliver(SyncMessageTypes.CHALLENGE, challengeBody())
        transport.awaitSent(SyncMessageTypes.AUTH)
        transport.awaitSent(SyncMessageTypes.KNOWN_VECTOR)

        transport.deliver(
            SyncMessageTypes.WANT_RANGES,
            WantRangesBody(listOf(OriginRangesDto(LOCAL_ID, listOf(RangeDto(1, 1_000_000))))),
        )
        val error = transport.awaitSent(SyncMessageTypes.ERROR).body as ErrorBody
        assertEquals(SyncErrorCodes.RATE_LIMITED, error.code)
        assertTrue(error.retryable)

        transport.peerCloses()
        assertTrue(result.await().authenticated)
    }
}

private fun CoroutineScope.launchEngine(
    engine: SyncEngine,
    transport: SyncTransport,
    result: CompletableDeferred<SyncSessionResult>,
) = launch {
    result.complete(engine.run(transport))
}
