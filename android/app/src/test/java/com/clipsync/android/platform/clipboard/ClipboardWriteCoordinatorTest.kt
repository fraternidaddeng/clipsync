package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardWriteCoordinatorTest {
    @Test
    fun `public writer is always attempted first`() {
        val publicWriter = FakeClipboardWriter()
        val fallbackWriter = FakeClipboardWriter()
        val coordinator = ClipboardWriteCoordinator(publicWriter, fallbackWriter)

        val outcome = coordinator.writeText("remote text", "event-1")

        assertEquals(ClipboardWriteResult.Success, outcome.result)
        assertEquals(ClipboardWriterKind.PUBLIC_API, outcome.writerKind)
        assertEquals(1, publicWriter.writes.size)
        assertEquals(0, fallbackWriter.writes.size)
    }

    @Test
    fun `ready fallback writer runs only after public failure`() {
        val publicWriter = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val fallbackWriter = FakeClipboardWriter()
        val coordinator = ClipboardWriteCoordinator(publicWriter, fallbackWriter)

        val outcome = coordinator.writeText("remote text", "event-2")

        assertEquals(ClipboardWriteResult.Success, outcome.result)
        assertEquals(ClipboardWriterKind.PRIVILEGED_FALLBACK, outcome.writerKind)
        assertEquals(1, publicWriter.writes.size)
        assertEquals(1, fallbackWriter.writes.size)
    }

    @Test
    fun `successful remote write suppresses one matching callback`() {
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = FakeClipboardWriter(),
            hasher = ContentHasher { "hash:$it" },
        )
        coordinator.writeText("remote text", "event-3")

        assertFalse(coordinator.shouldSuppress("event-3", "different"))
        assertTrue(coordinator.shouldSuppress("event-3", "remote text"))
        assertFalse(coordinator.shouldSuppress("event-3", "remote text"))
    }

    @Test
    fun `expired write suppression does not hide a later local copy`() {
        var now = 1_000L
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = FakeClipboardWriter(),
            hasher = ContentHasher { "hash:$it" },
            nowEpochMillis = { now },
            suppressionWindowMillis = 500L,
        )
        coordinator.writeText("same text", "event-4")
        now = 1_500L

        assertFalse(coordinator.shouldSuppress("event-4", "same text"))
    }

    @Test
    fun `failed write does not leave a suppression marker`() {
        val publicWriter = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = publicWriter,
            hasher = ContentHasher { "hash:$it" },
        )
        coordinator.writeText("remote text", "event-5")

        assertFalse(coordinator.shouldSuppress("event-5", "remote text"))
    }
}
