package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCaptureManagerTest {
    private val settings = SyncSettingsStore(FakeKeyValueStore())
    private var nowMillis = 10_000L
    private val outbox = KeyValueClipOutbox(FakeKeyValueStore(), nowEpochMillis = { nowMillis })
    private var nudges = 0
    private val writeCoordinator =
        ClipboardWriteCoordinator(
            publicWriter = FakeClipboardWriter(),
            nowEpochMillis = { nowMillis },
        )
    private val manager =
        ClipboardCaptureManager(
            settings = settings,
            writeCoordinator = writeCoordinator,
            outbox = { outbox },
            syncRequester = { SyncRequester { nudges++ } },
        )

    private fun change(text: String) = ClipboardChange(text, "hash:$text", nowMillis)

    @Test
    fun `captured text enters the outbox as a foreground event and nudges the engine`() =
        runBlocking {
            val outcome = manager.onClipboardChanged(change("copied on the phone"))

            assertEquals(CaptureOutcome.CAPTURED, outcome)
            val entry = outbox.pending().single()
            assertEquals("copied on the phone", entry.text)
            assertEquals(ClipSource.FOREGROUND_APP, entry.source)
            assertEquals(1, nudges)
        }

    @Test
    fun `private mode skips capture entirely`() =
        runBlocking {
            settings.privateMode = true

            assertEquals(CaptureOutcome.SKIPPED_PRIVATE_MODE, manager.onClipboardChanged(change("secret")))
            assertTrue(outbox.pending().isEmpty())
            assertEquals(0, nudges)
        }

    @Test
    fun `paused sync skips capture entirely`() =
        runBlocking {
            settings.syncPaused = true

            assertEquals(CaptureOutcome.SKIPPED_SYNC_PAUSED, manager.onClipboardChanged(change("held")))
            assertTrue(outbox.pending().isEmpty())
            assertEquals(0, nudges)
        }

    @Test
    fun `capture pause skips auto-capture without touching the outbox`() =
        runBlocking {
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
    fun `the global pause wins the outcome when both pauses are set`() =
        runBlocking {
            settings.syncPaused = true
            settings.autoCapturePaused = true

            assertEquals(CaptureOutcome.SKIPPED_SYNC_PAUSED, manager.onClipboardChanged(change("held")))
        }

    @Test
    fun `sensitive-marked clips enter neither history nor sync by default`() =
        runBlocking {
            // settings-roadmap P0-4: the preference defaults on — no setup required.
            val outcome = manager.onClipboardChanged(change("hunter2").copy(isSensitive = true))

            assertEquals(CaptureOutcome.SKIPPED_SENSITIVE, outcome)
            assertTrue(outbox.pending().isEmpty())
            assertEquals(0, nudges)

            // An unmarked clip still flows: only the source-app marker gates.
            assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change("plain note")))
        }

    @Test
    fun `turning the skip-sensitive preference off applies to the very next copy`() =
        runBlocking {
            settings.skipSensitiveEnabled = false

            val outcome = manager.onClipboardChanged(change("hunter2").copy(isSensitive = true))

            assertEquals(CaptureOutcome.CAPTURED, outcome)
            assertEquals("hunter2", outbox.pending().single().text)
        }

    @Test
    fun `private mode still wins the outcome over the sensitive marker`() =
        runBlocking {
            settings.privateMode = true

            val outcome = manager.onClipboardChanged(change("hunter2").copy(isSensitive = true))

            assertEquals(CaptureOutcome.SKIPPED_PRIVATE_MODE, outcome)
        }

    @Test
    fun `toggling pause off applies to the very next change`() =
        runBlocking {
            settings.syncPaused = true
            assertEquals(CaptureOutcome.SKIPPED_SYNC_PAUSED, manager.onClipboardChanged(change("one")))

            settings.syncPaused = false
            assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change("one")))
            assertEquals(1, outbox.pending().size)
        }

    @Test
    fun `a clip this app wrote itself is not re-captured`() =
        runBlocking {
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
    fun `a self write does not hide a different user copy`() =
        runBlocking {
            writeCoordinator.writeText("from windows", originEventId = "remote-event-2")

            assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change("typed by the user")))
            assertEquals("typed by the user", outbox.pending().single().text)
        }

    @Test
    fun `outbox rejections do not nudge the engine`() =
        runBlocking {
            manager.onClipboardChanged(change("same"))
            assertEquals(1, nudges)

            // Within the 2 s dedup window the outbox refuses the duplicate; no second nudge.
            val outcome = manager.onClipboardChanged(change("same"))
            assertEquals(CaptureOutcome.REJECTED_BY_OUTBOX, outcome)
            assertEquals(1, nudges)
            assertEquals(1, outbox.pending().size)
        }

    @Test
    fun `oversize text reports its own outcome so the user can be told`() =
        runBlocking {
            // One byte over the 1 MiB protocol cap: kept local, never truncated, never queued —
            // and distinguishable from quiet rejections so the capture path can prompt.
            val oversize = "x".repeat(KeyValueClipOutbox.MAX_UTF8_BYTES + 1)

            val outcome = manager.onClipboardChanged(change(oversize))

            assertEquals(CaptureOutcome.REJECTED_TOO_LARGE, outcome)
            assertTrue(outbox.pending().isEmpty())
            assertEquals(0, nudges)
        }

    @Test
    fun `exactly 1 MiB still syncs - the cap is not off by one`() =
        runBlocking {
            val atLimit = "x".repeat(KeyValueClipOutbox.MAX_UTF8_BYTES)

            assertEquals(CaptureOutcome.CAPTURED, manager.onClipboardChanged(change(atLimit)))
            assertEquals(1, outbox.pending().size)
        }

    @Test
    fun `image capture waits for persistence and a failed save is not counted as captured`() =
        runBlocking {
            settings.imageSyncEnabled = true
            val entered = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Boolean>()
            val imageManager =
                ClipboardCaptureManager(
                    settings = settings,
                    writeCoordinator = writeCoordinator,
                    imageSink = {
                        entered.complete(Unit)
                        saved.await()
                    },
                )
            val imageChange = change("").copy(imageBytes = byteArrayOf(1), imageMimeType = "image/png")
            val capture = async { imageManager.onClipboardChanged(imageChange) }
            withTimeout(5_000) { entered.await() }
            assertFalse(capture.isCompleted)

            saved.complete(false)
            assertEquals(CaptureOutcome.REJECTED_BY_OUTBOX, capture.await())
            assertEquals(0, nudges)
            assertTrue(outbox.pending().isEmpty())
        }
}
