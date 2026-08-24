package com.clipsync.android.platform.entry

import com.clipsync.android.platform.entry.ShareTextIntentHandler.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTextIntentHandlerTest {

    private val send = "android.intent.action.SEND"

    @Test
    fun `plain text share is accepted with the text unmodified`() {
        val outcome = ShareTextIntentHandler.classify(send, "text/plain", "  hello 世界\n")
        assertEquals(Outcome.ShareText("  hello 世界\n"), outcome)
    }

    @Test
    fun `other text subtypes with EXTRA_TEXT are accepted`() {
        val outcome = ShareTextIntentHandler.classify(send, "text/x-url", "https://example.com")
        assertEquals(Outcome.ShareText("https://example.com"), outcome)
    }

    @Test
    fun `char sequence is converted to its string form`() {
        val outcome = ShareTextIntentHandler.classify(send, "text/plain", StringBuilder("built"))
        assertEquals(Outcome.ShareText("built"), outcome)
    }

    @Test
    fun `missing action is not a share`() {
        assertEquals(Outcome.NotAShare, ShareTextIntentHandler.classify(null, "text/plain", "x"))
    }

    @Test
    fun `other actions are not a share`() {
        val outcome = ShareTextIntentHandler.classify("android.intent.action.VIEW", "text/plain", "x")
        assertEquals(Outcome.NotAShare, outcome)
    }

    @Test
    fun `send multiple is not a share`() {
        val outcome = ShareTextIntentHandler.classify("android.intent.action.SEND_MULTIPLE", "text/plain", "x")
        assertEquals(Outcome.NotAShare, outcome)
    }

    @Test
    fun `non-text mime type is unsupported`() {
        assertEquals(Outcome.UnsupportedContent, ShareTextIntentHandler.classify(send, "image/png", "x"))
    }

    @Test
    fun `missing mime type is unsupported`() {
        assertEquals(Outcome.UnsupportedContent, ShareTextIntentHandler.classify(send, null, "x"))
    }

    @Test
    fun `text share without extra text is rejected`() {
        assertEquals(Outcome.MissingText, ShareTextIntentHandler.classify(send, "text/plain", null))
    }

    @Test
    fun `empty extra text is rejected`() {
        assertEquals(Outcome.MissingText, ShareTextIntentHandler.classify(send, "text/plain", ""))
    }

    @Test
    fun `whitespace-only text is kept because content is never modified or judged`() {
        assertEquals(Outcome.ShareText("   "), ShareTextIntentHandler.classify(send, "text/plain", "   "))
    }
}
