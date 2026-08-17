package com.clipsync.android.ui.settings

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriterKind
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ClipServicesWriteCoordinatorTest {
    @Before
    fun resetSingleton() {
        ClipServices.resetWriteCoordinator()
    }

    @After
    fun clearSingleton() {
        ClipServices.resetWriteCoordinator()
    }

    @Test
    fun `non-READY fallback is never invoked so public-only behavior is unchanged`() {
        for (state in nonReadyStates()) {
            ClipServices.resetWriteCoordinator()
            val publicWriter = FakeClipboardWriter()
            val fallbackWriter = FakeClipboardWriter(state = state)
            val coordinator = ClipServices.writeCoordinator(publicWriter, fallbackWriter)

            val outcome = coordinator.writeText("remote text", "event-public-only")

            assertEquals(ClipboardWriteResult.Success, outcome.result)
            assertEquals(ClipboardWriterKind.PUBLIC_API, outcome.writerKind)
            assertEquals(1, publicWriter.writes.size)
            assertEquals(0, fallbackWriter.writes.size)
        }
    }

    @Test
    fun `public failure with non-READY fallback never writes via fallback`() {
        val publicWriter = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val fallbackWriter = FakeClipboardWriter(state = CapabilityState.NEEDS_USER_ACTION)
        val coordinator = ClipServices.writeCoordinator(publicWriter, fallbackWriter)

        val outcome = coordinator.writeText("remote text", "event-no-shizuku")

        assertEquals(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"), outcome.result)
        assertEquals(ClipboardWriterKind.PUBLIC_API, outcome.writerKind)
        assertEquals(1, publicWriter.writes.size)
        assertEquals(0, fallbackWriter.writes.size)
    }

    @Test
    fun `READY fallback is used only after public write fails`() {
        val publicWriter = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val fallbackWriter = FakeClipboardWriter(state = CapabilityState.READY)
        val coordinator = ClipServices.writeCoordinator(publicWriter, fallbackWriter)

        val outcome = coordinator.writeText("remote text", "event-fallback")

        assertEquals(ClipboardWriteResult.Success, outcome.result)
        assertEquals(ClipboardWriterKind.PRIVILEGED_FALLBACK, outcome.writerKind)
        assertEquals(1, publicWriter.writes.size)
        assertEquals(1, fallbackWriter.writes.size)
    }

    @Test
    fun `deferred provider slot stays public-only until a READY writer is attached`() {
        val publicWriter = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val attached = FakeClipboardWriter(state = CapabilityState.UNAVAILABLE)
        ClipServices.writeFallbackProvider = { attached }

        val coordinator = ClipServices.writeCoordinator(publicWriter)
        val before = coordinator.writeText("remote text", "event-deferred-1")
        assertEquals(ClipboardWriterKind.PUBLIC_API, before.writerKind)
        assertEquals(0, attached.writes.size)

        attached.state = CapabilityState.READY
        val after = coordinator.writeText("remote text", "event-deferred-2")
        assertEquals(ClipboardWriterKind.PRIVILEGED_FALLBACK, after.writerKind)
        assertEquals(1, attached.writes.size)
    }

    @Test
    fun `writeCoordinator remains a process singleton`() {
        val first = ClipServices.writeCoordinator(FakeClipboardWriter())
        val second = ClipServices.writeCoordinator(
            FakeClipboardWriter(),
            FakeClipboardWriter(state = CapabilityState.READY),
        )
        assertSame(first, second)
    }

    private fun nonReadyStates(): List<CapabilityState> = listOf(
        CapabilityState.UNKNOWN,
        CapabilityState.DEGRADED,
        CapabilityState.UNAVAILABLE,
        CapabilityState.NEEDS_USER_ACTION,
    )
}
