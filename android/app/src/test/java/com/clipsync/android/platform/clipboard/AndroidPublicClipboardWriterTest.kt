package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AndroidPublicClipboardWriterTest {
    @Test
    fun `successful setPrimaryClip maps to success`() {
        val os = FakeClipboardWriteOs(nextStatus = OsWriteStatus.SUCCESS)
        val writer = AndroidPublicClipboardWriter(os)

        val result = writer.writeText("remote text", "event-1")

        assertEquals(ClipboardWriteResult.Success, result)
        assertEquals(listOf("remote text"), os.writes)
    }

    @Test
    fun `system rejected write maps to stable error code`() {
        val os = FakeClipboardWriteOs(nextStatus = OsWriteStatus.REJECTED)
        val writer = AndroidPublicClipboardWriter(os)

        val result = writer.writeText("blocked text", "event-2")

        assertEquals(
            ClipboardWriteResult.Failure(AndroidPublicClipboardWriter.ERROR_REJECTED),
            result,
        )
        assertEquals(AndroidPublicClipboardWriter.ERROR_REJECTED, "PUBLIC_WRITE_REJECTED")
    }

    @Test
    fun `timeout write maps to stable error code`() {
        val os = FakeClipboardWriteOs(nextStatus = OsWriteStatus.TIMEOUT)
        val writer = AndroidPublicClipboardWriter(os)

        val result = writer.writeText("slow text", "event-3")

        assertEquals(
            ClipboardWriteResult.Failure(AndroidPublicClipboardWriter.ERROR_TIMEOUT),
            result,
        )
        assertEquals(AndroidPublicClipboardWriter.ERROR_TIMEOUT, "PUBLIC_WRITE_TIMEOUT")
    }

    @Test
    fun `unusable public api maps to unavailable without calling setPrimaryClip`() {
        val os = FakeClipboardWriteOs(usable = false)
        val writer = AndroidPublicClipboardWriter(os)

        val result = writer.writeText("unused text", "event-4")

        assertEquals(
            ClipboardWriteResult.Failure(AndroidPublicClipboardWriter.ERROR_UNAVAILABLE),
            result,
        )
        assertEquals(emptyList<String>(), os.writes)
    }

    @Test
    fun `probe is ready only when public api is usable`() {
        val os = FakeClipboardWriteOs(usable = true)
        val writer = AndroidPublicClipboardWriter(os)

        assertEquals(CapabilityState.READY, writer.probe())

        os.usable = false
        assertEquals(CapabilityState.UNAVAILABLE, writer.probe())
    }

    @Test
    fun `os exception maps to rejected without leaking clip text`() {
        val secret = "super-secret-clip-body"
        val os = FakeClipboardWriteOs { text ->
            throw IllegalStateException("denied: $text")
        }
        val writer = AndroidPublicClipboardWriter(os)

        val result = writer.writeText(secret, "event-5") as ClipboardWriteResult.Failure

        assertEquals(AndroidPublicClipboardWriter.ERROR_REJECTED, result.errorCode)
        assertFalse(result.errorCode.contains(secret))
        assertFalse(result.toString().contains(secret))
    }

    @Test
    fun `failure codes are the documented public write set`() {
        assertEquals(
            setOf(
                AndroidPublicClipboardWriter.ERROR_REJECTED,
                AndroidPublicClipboardWriter.ERROR_TIMEOUT,
                AndroidPublicClipboardWriter.ERROR_UNAVAILABLE,
            ),
            AndroidPublicClipboardWriter.ERROR_CODES,
        )
        assertTrue(AndroidPublicClipboardWriter.ERROR_CODES.all { it.startsWith("PUBLIC_WRITE_") })
    }

    @Test
    fun `share dir prune deletes superseded files but honors the paste grace window`() {
        withShareDir { shareDir ->
            val now = 10L * ShareDirPruner.GRACE_MS
            val stale = shareFile(shareDir, "stale-event.png", modifiedAt = now - ShareDirPruner.GRACE_MS - 1)
            val inGrace = shareFile(shareDir, "recent-event.jpg", modifiedAt = now - 1_000)
            val keep = shareFile(shareDir, "current-event.png", modifiedAt = now)

            ShareDirPruner.pruneSuperseded(shareDir, keep = keep, nowMs = now)

            // Superseded past the grace window: reclaimed. Within it: an app may still be
            // pasting that URI. The clipboard's current target always survives.
            assertFalse(stale.exists())
            assertTrue(inGrace.exists())
            assertTrue(keep.exists())
        }
    }

    @Test
    fun `share dir prune never deletes the kept file even with an ancient timestamp`() {
        withShareDir { shareDir ->
            // Re-apply of the same event id rewrites the same file; writeBytes can keep an
            // old mtime granularity on some filesystems, so `keep` must win by identity.
            val now = 10L * ShareDirPruner.GRACE_MS
            val keep = shareFile(shareDir, "same-event.png", modifiedAt = 1_000)

            ShareDirPruner.pruneSuperseded(shareDir, keep = keep, nowMs = now)

            assertTrue(keep.exists())
        }
    }

    private fun withShareDir(block: (File) -> Unit) {
        val dir = createTempDirectory("clipsync-share-prune").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun shareFile(
        dir: File,
        name: String,
        modifiedAt: Long,
    ): File =
        File(dir, name).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(setLastModified(modifiedAt))
        }

    private class FakeClipboardWriteOs(
        var usable: Boolean = true,
        var nextStatus: OsWriteStatus = OsWriteStatus.SUCCESS,
        private val onWrite: ((String) -> OsWriteStatus)? = null,
    ) : ClipboardWriteOs {
        val writes = mutableListOf<String>()

        override val isUsable: Boolean
            get() = usable

        override fun setPrimaryClip(text: String): OsWriteStatus {
            writes += text
            return onWrite?.invoke(text) ?: nextStatus
        }
    }
}
