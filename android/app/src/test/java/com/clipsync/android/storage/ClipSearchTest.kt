package com.clipsync.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipSearchTest {
    @Test
    fun `blank queries match everything`() {
        assertNull(ClipSearch.likePattern(""))
        assertNull(ClipSearch.likePattern("   "))
        assertNull(ClipSearch.likePattern("\n\t"))
    }

    @Test
    fun `plain text becomes a contains pattern`() {
        assertEquals("%git%", ClipSearch.likePattern("git"))
        assertEquals("%会议纪要%", ClipSearch.likePattern(" 会议纪要 "))
    }

    @Test
    fun `sql wildcards are escaped so they match literally`() {
        assertEquals("%100\\%%", ClipSearch.likePattern("100%"))
        assertEquals("%a\\_b%", ClipSearch.likePattern("a_b"))
        assertEquals("%c:\\\\temp%", ClipSearch.likePattern("c:\\temp"))
    }
}
