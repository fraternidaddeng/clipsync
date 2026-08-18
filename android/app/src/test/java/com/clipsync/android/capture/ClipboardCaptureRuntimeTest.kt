package com.clipsync.android.capture

import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.storage.CapturePolicy
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.createTestClipRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCaptureRuntimeTest {
    @Test
    fun `capture source tag is the active read mode`() {
        assertEquals("shizuku", captureSourceTag(ClipboardReadMode.SHIZUKU_EVENT))
        assertEquals("adb", captureSourceTag(ClipboardReadMode.ADB_LOG_OVERLAY))
        assertEquals("overlay", captureSourceTag(ClipboardReadMode.OVERLAY_POLLING))
        assertEquals("foreground", captureSourceTag(ClipboardReadMode.FOREGROUND_ONLY))
        assertEquals("shizuku", captureSourceTag(null))
    }

    @Test
    fun `capture stores the active-mode tag`() =
        runTest {
            val repo = createTestClipRepository()
            val stored =
                repo.captureLocalText(
                    "from overlay",
                    sourceApp = captureSourceTag(ClipboardReadMode.OVERLAY_POLLING),
                    nowMs = NOW,
                )
            assertTrue(stored is CaptureResult.Stored)
            assertEquals("overlay", repo.search("").single().sourceApp)
            assertTrue("overlay" in CapturePolicy.INTERNAL_SOURCE_TAGS)
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
