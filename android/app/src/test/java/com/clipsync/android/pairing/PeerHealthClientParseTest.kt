package com.clipsync.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tolerant parsing of the health payload's `clipboard_apply_text` self-report.
 * The rule under test: absence of information maps to null ("not reported"),
 * never to a guessed state — the conduit page turns null into an honest 未探测.
 */
class PeerHealthClientParseTest {
    private fun body(token: String) =
        """{"version":1,"device_id":"11111111-1111-4111-8111-111111111111",""" +
            """"port":47654,"clipboard_apply_text":"$token"}"""

    @Test
    fun `parses every published token`() {
        assertEquals(PeerClipboardApply.OFF, PeerHealthClient.parseClipboardApply(body("off")))
        assertEquals(PeerClipboardApply.PAUSED, PeerHealthClient.parseClipboardApply(body("paused")))
        assertEquals(PeerClipboardApply.UNVERIFIED, PeerHealthClient.parseClipboardApply(body("unverified")))
        assertEquals(PeerClipboardApply.APPLIED, PeerHealthClient.parseClipboardApply(body("applied")))
        assertEquals(PeerClipboardApply.FAILED, PeerHealthClient.parseClipboardApply(body("failed")))
    }

    @Test
    fun `older peer without the field reports null, not bad news`() {
        val legacy = """{"version":1,"device_id":"x","port":47654}"""
        assertNull(PeerHealthClient.parseClipboardApply(legacy))
    }

    @Test
    fun `unknown future token maps to null instead of a guess`() {
        assertNull(PeerHealthClient.parseClipboardApply(body("hyper-applied-v9")))
    }

    @Test
    fun `malformed or empty bodies never throw`() {
        assertNull(PeerHealthClient.parseClipboardApply(null))
        assertNull(PeerHealthClient.parseClipboardApply(""))
        assertNull(PeerHealthClient.parseClipboardApply("not json at all"))
        assertNull(PeerHealthClient.parseClipboardApply("""{"clipboard_apply_text":42}"""))
        assertNull(PeerHealthClient.parseClipboardApply("""{"clipboard_apply_text":null}"""))
        assertNull(PeerHealthClient.parseClipboardApply("[1,2,3]"))
    }
}
