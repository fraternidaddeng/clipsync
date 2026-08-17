package com.clipsync.android.protocol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMessageParseTest {
    @Test
    fun `all shared valid fixtures parse to typed bodies`() {
        val files = fixtureFiles("valid")
        assertEquals(
            "valid fixtures must cover every v1 message type",
            ProtocolMessageTypes.ALL,
            files.map { it.nameWithoutExtension }.toSet(),
        )
        files.forEach { fixture ->
            val parsed = SyncMessages.parse(fixture.readText())
            assertEquals(1, parsed.version)
            assertEquals(fixture.nameWithoutExtension, parsed.type)
            assertEquals(expectedBodyClass(parsed.type), parsed.body::class.java)
        }
    }

    @Test
    fun `hello fixture maps typed fields`() {
        val parsed = SyncMessages.parse(readValid("hello.json"))
        val body = parsed.body as HelloBody
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", parsed.requestId)
        assertEquals("11111111-1111-4111-8111-111111111111", body.deviceId)
        assertEquals("windows", body.platform)
        assertEquals("0.1.0", body.clientVersion)
        assertEquals(1L, body.trustEpoch)
        assertEquals(1, body.knownVector.origins.size)
        val origin = body.knownVector.origins.single()
        assertEquals("11111111-1111-4111-8111-111111111111", origin.originDeviceId)
        assertEquals(3L, origin.contiguousSeq)
        assertEquals(listOf(RangeDto(5, 6)), origin.receivedRanges)
    }

    @Test
    fun `challenge fixture maps typed fields and hmac-sha256`() {
        val body = SyncMessages.parse(readValid("challenge.json")).body as ChallengeBody
        assertEquals(PairAuthProof.ALGORITHM, body.algorithm)
        assertEquals("hmac-sha256", body.algorithm)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", body.nonce)
        assertEquals("11111111-1111-4111-8111-111111111111", body.challengerDeviceId)
        assertEquals("22222222-2222-4222-8222-222222222222", body.responderDeviceId)
        assertEquals(1L, body.trustEpoch)
        assertEquals(1_776_000_000_000L, body.expiresAtMs)
    }

    @Test
    fun `auth fixture maps typed fields and hmac-sha256`() {
        val body = SyncMessages.parse(readValid("auth.json")).body as AuthBody
        assertEquals(PairAuthProof.ALGORITHM, body.algorithm)
        assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", body.challengeRequestId)
        assertEquals("22222222-2222-4222-8222-222222222222", body.responderDeviceId)
        assertEquals(1L, body.trustEpoch)
        assertEquals("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8", body.proof)
    }

    @Test
    fun `known_vector fixture maps origins and omitted ranges`() {
        val body = SyncMessages.parse(readValid("known_vector.json")).body as SyncStateDto
        assertEquals(2, body.origins.size)
        assertEquals("11111111-1111-4111-8111-111111111111", body.origins[0].originDeviceId)
        assertEquals(10L, body.origins[0].contiguousSeq)
        assertNull(body.origins[0].receivedRanges)
        assertEquals("22222222-2222-4222-8222-222222222222", body.origins[1].originDeviceId)
        assertEquals(4L, body.origins[1].contiguousSeq)
        assertEquals(listOf(RangeDto(7, 8)), body.origins[1].receivedRanges)
    }

    @Test
    fun `want_ranges fixture maps inclusive ranges`() {
        val body = SyncMessages.parse(readValid("want_ranges.json")).body as WantRangesBody
        val request = body.requests.single()
        assertEquals("11111111-1111-4111-8111-111111111111", request.originDeviceId)
        assertEquals(listOf(RangeDto(4, 4), RangeDto(7, 9)), request.ranges)
    }

    @Test
    fun `clip_announce fixture maps available and unavailable headers`() {
        val body = SyncMessages.parse(readValid("clip_announce.json")).body as ClipAnnounceBody
        assertEquals(2, body.clips.size)
        val available = body.clips[0]
        assertEquals("33333333-3333-4333-8333-333333333333", available.eventId)
        assertEquals("11111111-1111-4111-8111-111111111111", available.originDeviceId)
        assertEquals(4L, available.originSeq)
        assertEquals(ClipAvailability.AVAILABLE, available.availability)
        assertEquals("text", available.kind)
        assertEquals("7ee46a9cda0560475782f6d67f83924d1aa6e5d1565e074d1c1b499fb48cdbd1", available.contentHash)
        assertEquals(22L, available.utf8Bytes)
        assertEquals("notepad.exe", available.sourceApp)
        assertEquals(1_776_000_000_000L, available.createdAtMs)
        assertEquals(1_778_592_000_000L, available.expiresAtMs)
        assertNull(available.reason)

        val unavailable = body.clips[1]
        assertEquals("44444444-4444-4444-8444-444444444444", unavailable.eventId)
        assertEquals(ClipAvailability.UNAVAILABLE, unavailable.availability)
        assertEquals(ClipUnavailableReasons.LOCAL_ONLY, unavailable.reason)
        assertNull(unavailable.kind)
        assertNull(unavailable.contentHash)
    }

    @Test
    fun `clip_fetch fixture maps event ids`() {
        val body = SyncMessages.parse(readValid("clip_fetch.json")).body as ClipFetchBody
        assertEquals(listOf("33333333-3333-4333-8333-333333333333"), body.eventIds)
    }

    @Test
    fun `clip_payload fixture maps identity without logging content`() {
        val body = SyncMessages.parse(readValid("clip_payload.json")).body as ClipPayloadBody
        val clip = body.clips.single()
        assertEquals("33333333-3333-4333-8333-333333333333", clip.eventId)
        assertEquals("11111111-1111-4111-8111-111111111111", clip.originDeviceId)
        assertEquals(4L, clip.originSeq)
        assertEquals("text", clip.kind)
        assertEquals("7ee46a9cda0560475782f6d67f83924d1aa6e5d1565e074d1c1b499fb48cdbd1", clip.contentHash)
        assertEquals(22L, clip.utf8Bytes)
        assertEquals(clip.utf8Bytes, clip.content.toByteArray(Charsets.UTF_8).size.toLong())
        assertEquals("notepad.exe", clip.sourceApp)
        assertEquals(1_776_000_000_000L, clip.createdAtMs)
        assertEquals(1_778_592_000_000L, clip.expiresAtMs)
    }

    @Test
    fun `ack_ranges fixture maps acks`() {
        val body = SyncMessages.parse(readValid("ack_ranges.json")).body as AckRangesBody
        val ack = body.acks.single()
        assertEquals("11111111-1111-4111-8111-111111111111", ack.originDeviceId)
        assertEquals(listOf(RangeDto(1, 4)), ack.ranges)
    }

    @Test
    fun `error fixture maps stable code`() {
        val body = SyncMessages.parse(readValid("error.json")).body as ErrorBody
        assertEquals(ProtocolErrorCodes.RATE_LIMITED, body.code)
        assertTrue(body.retryable)
        assertEquals(ProtocolMessageTypes.CLIP_PAYLOAD, body.failedType)
        assertEquals(1000L, body.retryAfterMs)
    }

    @Test
    fun `ping and pong fixtures map timestamps`() {
        val ping = SyncMessages.parse(readValid("ping.json")).body as PingBody
        assertEquals(1_776_000_000_000L, ping.sentAtMs)
        val pong = SyncMessages.parse(readValid("pong.json")).body as PongBody
        assertEquals(1_776_000_000_000L, pong.pingSentAtMs)
        assertEquals(1_776_000_000_010L, pong.sentAtMs)
    }

    @Test
    fun `all shared invalid fixtures stay rejected by ProtocolJson`() {
        val files = fixtureFiles("invalid")
        files.forEach { fixture ->
            val rejected = runCatching { ProtocolJson.parseEnvelope(fixture.readText()) }.isFailure
            assertTrue("Invalid fixture was accepted: ${fixture.name}", rejected)
            val typedRejected = runCatching { SyncMessages.parse(fixture.readText()) }.isFailure
            assertTrue("Typed parse accepted invalid fixture: ${fixture.name}", typedRejected)
        }
    }

    private fun expectedBodyClass(type: String): Class<*> = when (type) {
        ProtocolMessageTypes.HELLO -> HelloBody::class.java
        ProtocolMessageTypes.CHALLENGE -> ChallengeBody::class.java
        ProtocolMessageTypes.AUTH -> AuthBody::class.java
        ProtocolMessageTypes.KNOWN_VECTOR -> SyncStateDto::class.java
        ProtocolMessageTypes.WANT_RANGES -> WantRangesBody::class.java
        ProtocolMessageTypes.CLIP_ANNOUNCE -> ClipAnnounceBody::class.java
        ProtocolMessageTypes.CLIP_FETCH -> ClipFetchBody::class.java
        ProtocolMessageTypes.CLIP_PAYLOAD -> ClipPayloadBody::class.java
        ProtocolMessageTypes.ACK_RANGES -> AckRangesBody::class.java
        ProtocolMessageTypes.ERROR -> ErrorBody::class.java
        ProtocolMessageTypes.PING -> PingBody::class.java
        ProtocolMessageTypes.PONG -> PongBody::class.java
        else -> error("unexpected type $type")
    }

    private fun readValid(name: String): String = fixtureRoot().resolve("valid").resolve(name).readText()

    private fun fixtureFiles(subset: String): List<File> {
        val dir = fixtureRoot().resolve(subset)
        assertTrue("Protocol fixture directory is missing: $dir", dir.isDirectory)
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue("$subset protocol fixture set must not be empty.", files.isNotEmpty())
        return files
    }

    private fun fixtureRoot(): File {
        val fromProperty = System.getProperty("protocol.fixtures.dir")
        val candidates = listOfNotNull(
            fromProperty?.let(::File),
            File("protocol/v1/fixtures"),
            File("../protocol/v1/fixtures"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot resolve protocol/v1/fixtures")
    }
}
