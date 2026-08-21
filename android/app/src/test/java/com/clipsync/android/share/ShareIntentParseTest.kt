package com.clipsync.android.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentParseTest {
    @Test
    fun `gallery image mime types are treated as image shares`() {
        assertTrue("image/png".looksLikeImageShare())
        assertTrue("image/jpeg".looksLikeImageShare())
        assertTrue("image/jpg".looksLikeImageShare())
        assertTrue("image/*".looksLikeImageShare())
        assertTrue("IMAGE/JPEG; charset=binary".looksLikeImageShare())
        assertFalse("text/plain".looksLikeImageShare())
        assertFalse(null.looksLikeImageShare())
        assertFalse("application/pdf".looksLikeImageShare())
    }
}
