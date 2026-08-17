package com.clipsync.android.notify

import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.storage.createTestClipRepository
import com.clipsync.android.ui.settings.SETTING_AUTO_APPLY_REMOTE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundClipApplierTest {
    @Test
    fun `auto-apply off offers copy and does not write`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_AUTO_APPLY_REMOTE, "false")
        val writer = FakeClipboardWriter()
        val offered = mutableListOf<String>()
        val applier = InboundClipApplier(
            repository = repo,
            writeCoordinator = ClipboardWriteCoordinator(writer),
            offerManualCopy = { offered += it },
        )
        applier.onCommitted(listOf(InboundClip("evt-1", "remote text")))
        assertTrue(writer.writes.isEmpty())
        assertEquals(listOf("evt-1"), offered)
    }

    @Test
    fun `write failure offers copy`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_AUTO_APPLY_REMOTE, "true")
        val writer = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val offered = mutableListOf<String>()
        val applier = InboundClipApplier(
            repository = repo,
            writeCoordinator = ClipboardWriteCoordinator(writer),
            offerManualCopy = { offered += it },
        )
        applier.onCommitted(listOf(InboundClip("evt-2", "remote text")))
        assertEquals(1, writer.writes.size)
        assertEquals(listOf("evt-2"), offered)
    }

    @Test
    fun `successful auto-apply does not offer copy`() = runTest {
        val repo = createTestClipRepository()
        val writer = FakeClipboardWriter()
        val offered = mutableListOf<String>()
        val applier = InboundClipApplier(
            repository = repo,
            writeCoordinator = ClipboardWriteCoordinator(writer),
            offerManualCopy = { offered += it },
        )
        applier.onCommitted(listOf(InboundClip("evt-3", "remote text")))
        assertEquals(1, writer.writes.size)
        assertTrue(offered.isEmpty())
    }
}
