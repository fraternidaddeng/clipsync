package com.clipsync.android.sync

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
import com.clipsync.android.protocol.PingBody
import com.clipsync.android.protocol.PongBody
import com.clipsync.android.protocol.ProtocolMessageTypes
import com.clipsync.android.protocol.RangeDto
import com.clipsync.android.protocol.SyncMessageWriter
import com.clipsync.android.protocol.SyncMessages
import com.clipsync.android.protocol.SyncStateDto
import com.clipsync.android.protocol.WantRangesBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMessageWriterTest {
    @Test
    fun `encoded hello parses and omits empty received_ranges`() {
        val body = HelloBody(
            deviceId = LOCAL,
            platform = "android",
            clientVersion = "0.1.0",
            trustEpoch = 1,
            knownVector = SyncStateDto(
                listOf(OriginStateDto(LOCAL, 3, receivedRanges = null)),
            ),
        )
        val json = SyncMessageWriter.encode(ProtocolMessageTypes.HELLO, REQUEST, body)
        val parsed = SyncMessages.parse(json)
        assertEquals(ProtocolMessageTypes.HELLO, parsed.type)
        val hello = parsed.body as HelloBody
        assertEquals("android", hello.platform)
        assertEquals(1L, hello.trustEpoch)
        assertEquals(3L, hello.knownVector.origins.single().contiguousSeq)
        assertFalse(json.contains("null"))
        assertFalse(json.contains("received_ranges"))
    }

    @Test
    fun `every outgoing type round-trips through SyncMessages parse`() {
        val samples = listOf(
            HelloBody(LOCAL, "android", "0.1.0", 1, SyncStateDto(emptyList())),
            ChallengeBody(
                PairAuthProof.ALGORITHM,
                PairAuthProof.encodeBase64Url(ByteArray(32) { it.toByte() }),
                PEER,
                LOCAL,
                1,
                1_776_000_000_000,
            ),
            AuthBody(PairAuthProof.ALGORITHM, REQUEST, LOCAL, 1, PairAuthProof.encodeBase64Url(ByteArray(32))),
            SyncStateDto(listOf(OriginStateDto(PEER, 10, listOf(RangeDto(12, 12))))),
            WantRangesBody(listOf(OriginRangesDto(PEER, listOf(RangeDto(11, 12))))),
            ClipAnnounceBody(
                listOf(
                    ClipHeaderDto(
                        eventId = EVENT,
                        originDeviceId = PEER,
                        originSeq = 1,
                        availability = ClipAvailability.UNAVAILABLE,
                        reason = "deleted",
                    ),
                ),
            ),
            ClipFetchBody(listOf(EVENT)),
            ClipPayloadBody(
                listOf(
                    ClipPayloadItemDto(
                        eventId = EVENT,
                        originDeviceId = PEER,
                        originSeq = 1,
                        kind = "text",
                        content = "hi",
                        contentHash = HASH_HI,
                        utf8Bytes = 2,
                        createdAtMs = 1_700_000_000_000,
                    ),
                ),
            ),
            AckRangesBody(listOf(OriginRangesDto(PEER, listOf(RangeDto(1, 1))))),
            ErrorBody(code = "AUTH_FAILED", retryable = false),
            PingBody(1_700_000_000_000),
            PongBody(1_700_000_000_000, 1_700_000_000_100),
        )
        for (body in samples) {
            val parsed = SyncMessages.parse(SyncMessageWriter.encode(body))
            assertEquals(body::class.java, parsed.body::class.java)
        }
    }

    @Test
    fun `request ids are canonical lowercase non-nil uuids`() {
        val id = SyncMessageWriter.newRequestId()
        assertTrue(id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
        assertTrue(id.none { it.isUpperCase() })
    }

    companion object {
        private const val LOCAL = "22222222-2222-4222-8222-222222222222"
        private const val PEER = "11111111-1111-4111-8111-111111111111"
        private const val REQUEST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val EVENT = "33333333-3333-4333-8333-333333333333"
        private const val HASH_HI = "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4"
    }
}
