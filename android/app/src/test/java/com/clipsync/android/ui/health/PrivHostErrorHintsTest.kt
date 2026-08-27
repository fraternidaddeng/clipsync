package com.clipsync.android.ui.health

import com.clipsync.android.i18n.testString
import com.clipsync.android.platform.clipboard.ClipboardSelfTest
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closed 特权直读 error-code set (plan 5.3) explains itself: every stable code the
 * privileged host or its backend can surface carries a one-line human hint beside the
 * machine code, while codes outside the closed set stay silent — no invented guesses.
 */
class PrivHostErrorHintsTest {
    @Test
    fun `every code in the closed PRIV_HOST set carries a hint`() {
        ShizukuErrorCodes.ALL.forEach { code ->
            val hint = PrivHostErrorHints.hintFor(code)
            assertNotNull("no hint for $code", hint)
            assertTrue("blank hint for $code", hint!!.testString().isNotBlank())
        }
    }

    @Test
    fun `each condition gets its own explanation, not a shared shrug`() {
        val hints = ShizukuErrorCodes.ALL.map { PrivHostErrorHints.hintFor(it)!!.testString() }
        assertEquals(hints.size, hints.toSet().size)
    }

    @Test
    fun `the backend's channel codes share the hints of the conditions they name`() {
        // PRIVILEGED_CHANNEL_MISSING / PRIV_HOST_NOT_INSTALLED describe the same fact,
        // so the user reads the same sentence whichever layer reported it.
        assertEquals(
            PrivHostErrorHints.hintFor(ShizukuErrorCodes.NOT_INSTALLED),
            PrivHostErrorHints.hintFor(ShizukuClipboardBackend.ERROR_CHANNEL_MISSING),
        )
        assertEquals(
            PrivHostErrorHints.hintFor(ShizukuErrorCodes.NOT_RUNNING),
            PrivHostErrorHints.hintFor(ShizukuClipboardBackend.ERROR_CHANNEL_OFFLINE),
        )
        assertEquals(
            PrivHostErrorHints.hintFor(ShizukuErrorCodes.NOT_AUTHORIZED),
            PrivHostErrorHints.hintFor(ShizukuClipboardBackend.ERROR_PERMISSION_DENIED),
        )
        // 待实测 is not an error but still deserves its next step in words.
        assertNotNull(PrivHostErrorHints.hintFor(ShizukuClipboardBackend.ERROR_READ_UNVERIFIED))
    }

    @Test
    fun `codes outside the closed set stay silent`() {
        assertNull(PrivHostErrorHints.hintFor(null))
        assertNull(PrivHostErrorHints.hintFor(ClipboardSelfTest.ERROR_READ_EMPTY))
        assertNull(PrivHostErrorHints.hintFor("SOME_FUTURE_CODE"))
    }
}
