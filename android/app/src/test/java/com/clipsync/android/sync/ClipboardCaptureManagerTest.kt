package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.storage.SyncSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCaptureManagerTest {
    private val settings = SyncSettingsStore(FakeKeyValueStore())
    private var nowMillis = 10_000L
    private val outbox = KeyValueClipOutbox(FakeKeyValueStore(), nowEpochMillis = { nowMillis })
    private var nudges = 0
    private val writeCoordinator = ClipboardWriteCoordinator(
        publicWriter = FakeClipboardWriter(),
        nowEpochMillis = { nowMillis },
    )
    private val manager = ClipboardCaptureManager(
        settings = settings,
        writeCoordinator = writeCoordinator,
        outbox = { outbox },
        syncRequester = { SyncRequester { nudges++ } },
    )

    private fun change(text: String) = ClipboardChange(text, "hash:$text", nowMillis)

    @Test
    fun `captured text enters the outbox as a foreground event and nudges the engine`() {
        val outcome = manager.onClipboardChanged(change("copied on the phone"))

        assertEquals(CaptureOutcome.CAPTURED, outcome)
        val entry = outbox.pending().single()
        assertEquals("copied on the phone", entry.text)
        assertEquals(ClipSource.FOREGROUND_APP, entry.source)
        assertEquals(1, nudges)
    }

    @Test
    fun `private mode skips capture entirely`() {
        settings.privateMode = true

        assertEquals(CaptureOutcome.SKIPPED_PRIVATE_MODE, manager.onClipboardChanged(change("secret")))
        assertTrue(outbox.pending().isEmpty())
        assertEquals(0, nudges)
    }

    @Test
    fun `paused sync skips capture entirely`() {
        settings.syncPaused = true

        assertEquals(CaptureOutcome.SKIPPED_SYNC_PAUSED, manager.onClipboardChanged(change("held")))
        assertTrue(outbox.pending().isEmpty())
        assertEquals(0, nudges)
    }

    @Test
    fun `capture pause skips auto-capture without touching the outbox`() {
        settings.autoCapturePaused = true

        assertEquals(CaptureOutcome.SKIPPED_CAPTURE_PAUSED, manager.onClipboardChanged(change("held")))
        assertTrue(outbox.pending().isEmpty())
        assertEquals(0, nudges)

        // Toggling it back on (via the notification action) applies to the next change.
        settings.autoCapturePaused = false
        assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change("held")))
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `the global pause wins the outcome when both pauses are set`() {
        settings.syncPaused = true
        settings.autoCapturePaused = true

        assertEquals(CaptureOutcome.SKIPPED_SYNC_PAUSED, manager.onClipboardChanged(change("held")))
    }

    @Test
    fun `toggling pause off applies to the very next change`() {
        settings.syncPaused = true
        assertEquals(CaptureOutcome.SKIPPED_SYNC_PAUSED, manager.onClipboardChanged(change("one")))

        settings.syncPaused = false
        assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change("one")))
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `a clip this app wrote itself is not re-captured`() {
        // Inbound auto-apply (or a history copy) goes through the shared write coordinator.
        writeCoordinator.writeText("from windows", originEventId = "remote-event-1")

        assertEquals(CaptureOutcome.SKIPPED_OWN_WRITE, manager.onClipboardChanged(change("from windows")))
        assertTrue(outbox.pending().isEmpty())
        assertEquals(0, nudges)

        // The suppression is consumed: a genuine later copy of the same text still syncs.
        assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change("from windows")))
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `a self write does not hide a different user copy`() {
        writeCoordinator.writeText("from windows", originEventId = "remote-event-2")

        assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change("typed by the user")))
        assertEquals("typed by the user", outbox.pending().single().text)
    }

    @Test
    fun `outbox rejections do not nudge the engine`() {
        manager.onClipboardChanged(change("same"))
        assertEquals(1, nudges)

        // Within the 2 s dedup window the outbox refuses the duplicate; no second nudge.
        val outcome = manager.onClipboardChanged(change("same"))
        assertEquals(CaptureOutcome.REJECTED_BY_OUTBOX, outcome)
        assertEquals(1, nudges)
        assertEquals(1, outbox.pending().size)
    }
}
