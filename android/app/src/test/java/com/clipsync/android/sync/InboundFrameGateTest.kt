package com.clipsync.android.sync

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame-size hardening at the WebSocket boundary (plan 阶段 6: 校验 WebSocket 帧大小):
 * the allocation-free UTF-8 measurement must agree exactly with `toByteArray(UTF_8)`,
 * and the inbound gate must keep the not-yet-consumed backlog bounded.
 */
class InboundFrameGateTest {
    private fun utf8Size(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    @Test
    fun `utf8 counting matches toByteArray across character widths`() {
        val samples = listOf(
            "",
            "abc",
            "汉字剪贴板",
            "mixed 中文 and ascii",
            "emoji \uD83D\uDE00\uD83C\uDF89", // surrogate pairs: 4 bytes each
            "\u00e9\u00e8\u00ea", // 2-byte characters
            "\uD800", // unpaired high surrogate: encoder writes '?' (1 byte)
            "tail\uDC00", // unpaired low surrogate
            "\uD800a\uDC00", // surrogates split by a regular character
        )
        for (sample in samples) {
            val exact = utf8Size(sample)
            assertFalse(sample, SyncLimits.utf8BytesExceed(sample, exact))
            if (exact > 0) {
                assertTrue(sample, SyncLimits.utf8BytesExceed(sample, exact - 1))
            }
        }
    }

    @Test
    fun `multi-byte text over the limit is caught even when the char count is under it`() {
        // 10 chars but 30 UTF-8 bytes: the old length-only fast path alone would miss this.
        val text = "汉".repeat(10)
        assertEquals(30, utf8Size(text))
        assertTrue(SyncLimits.utf8BytesExceed(text, 29))
        assertFalse(SyncLimits.utf8BytesExceed(text, 30))
    }

    @Test
    fun `gate maps sizes to verdicts at the exact boundary`() {
        val gate = InboundFrameGate(maxMessageBytes = 8, maxBufferedChars = 1_000)
        assertEquals(InboundFrameGate.Verdict.ACCEPT, gate.onText("12345678"))
        assertEquals(InboundFrameGate.Verdict.TOO_LARGE, gate.onText("123456789"))
        // 3 chars, 9 UTF-8 bytes.
        assertEquals(InboundFrameGate.Verdict.TOO_LARGE, gate.onText("汉汉汉"))
    }

    @Test
    fun `backlog above the buffer bound is an overflow until frames are consumed`() {
        val gate = InboundFrameGate(maxMessageBytes = 100, maxBufferedChars = 10)
        assertEquals(InboundFrameGate.Verdict.ACCEPT, gate.onText("12345"))
        assertEquals(InboundFrameGate.Verdict.ACCEPT, gate.onText("12345"))
        // 11th queued char crosses the bound: the caller must kill the socket.
        assertEquals(InboundFrameGate.Verdict.OVERFLOW, gate.onText("x"))

        // Consuming a queued frame frees budget for the next accept.
        gate.onConsumed("12345")
        assertEquals(InboundFrameGate.Verdict.ACCEPT, gate.onText("abc"))
    }

    @Test
    fun `oversized frames do not consume buffer budget`() {
        val gate = InboundFrameGate(maxMessageBytes = 4, maxBufferedChars = 8)
        assertEquals(InboundFrameGate.Verdict.TOO_LARGE, gate.onText("12345"))
        assertEquals(InboundFrameGate.Verdict.ACCEPT, gate.onText("1234"))
        assertEquals(InboundFrameGate.Verdict.ACCEPT, gate.onText("1234"))
    }
}
