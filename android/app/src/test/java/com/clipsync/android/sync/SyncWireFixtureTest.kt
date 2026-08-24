package com.clipsync.android.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typed decode/encode must agree with the shared protocol fixtures on every message type. */
class SyncWireFixtureTest {
    private fun fixture(name: String): String =
        File(requireNotNull(System.getProperty("protocol.fixtures.dir")), "valid/$name.json").readText()

    @Test
    fun `every valid fixture decodes to its typed body and re-encodes equivalently`() {
        val root = File(requireNotNull(System.getProperty("protocol.fixtures.dir")), "valid")
        val files = root.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("Valid fixture set must not be empty.", files.isNotEmpty())

        files.forEach { file ->
            val decoded = SyncWire.decode(file.readText())
            // Re-encoding with the same request id must survive the strict validator and
            // decode back to an identical body (optional nulls must be omitted, not sent).
            val reDecoded = SyncWire.decode(SyncWire.encode(decoded.type, decoded.requestId, decoded.body))
            assertEquals("Round-trip mismatch for ${file.name}", decoded.body, reDecoded.body)
            assertEquals(decoded.requestId, reDecoded.requestId)
        }
    }

    @Test
    fun `hello fixture carries identity and the vector snapshot`() {
        val hello = SyncWire.decode(fixture("hello")).body as HelloBody
        assertEquals("11111111-1111-4111-8111-111111111111", hello.deviceId)
        assertEquals("windows", hello.platform)
        assertEquals(1, hello.trustEpoch)
        val origin = hello.knownVector.origins.single()
        assertEquals(3, origin.contiguousSeq)
        assertEquals(listOf(RangeDto(5, 6)), origin.receivedRanges)
    }

    @Test
    fun `challenge and auth fixtures carry the handshake fields`() {
        val challenge = SyncWire.decode(fixture("challenge")).body as ChallengeBody
        assertEquals(HMAC_ALGORITHM, challenge.algorithm)
        assertEquals(32, Base64Url.decodeExact(challenge.nonce, 32)?.size)

        val auth = SyncWire.decode(fixture("auth")).body as AuthBody
        assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", auth.challengeRequestId)
        assertEquals(32, Base64Url.decodeExact(auth.proof, 32)?.size)
    }

    @Test
    fun `clip payload fixture matches its own announced hash`() {
        val payload = SyncWire.decode(fixture("clip_payload")).body as ClipPayloadBody
        val clip = payload.clips.single()
        assertEquals("你好，ClipSync 👋", clip.content)
        assertEquals(22, clip.utf8Bytes)

        val announce = SyncWire.decode(fixture("clip_announce")).body as ClipAnnounceBody
        val available = announce.clips.first { it.availability == ClipAvailability.AVAILABLE }
        assertEquals(clip.contentHash, available.contentHash)
        val unavailable = announce.clips.first { it.availability == ClipAvailability.UNAVAILABLE }
        assertEquals("local_only", unavailable.reason)
    }
}
