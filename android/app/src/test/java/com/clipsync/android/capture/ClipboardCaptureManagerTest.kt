package com.clipsync.android.capture

import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.ui.wizard.WizardChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardCaptureManagerTest {
    private class Harness(
        rebuildDebounceMs: Long = 0L,
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
    ) {
        val callLog = mutableListOf<String>()
        val captured = mutableListOf<String>()
        var buildCount = 0
        var releaseOverlayCount = 0
        var lastIsVisible: (() -> Boolean)? = null
        var currentBackend: FakeBackgroundClipboardBackend? = null

        val manager =
            ClipboardCaptureManager(
                loadChoices = { WizardChoices() },
                buildStack = { _, isVisible ->
                    buildCount += 1
                    lastIsVisible = isVisible
                    val shizuku =
                        FakeBackgroundClipboardBackend(
                            mode = ClipboardReadMode.SHIZUKU_EVENT,
                            callLog = callLog,
                        )
                    val foreground =
                        FakeBackgroundClipboardBackend(
                            mode = ClipboardReadMode.FOREGROUND_ONLY,
                            callLog = callLog,
                        )
                    currentBackend = shizuku
                    ClipboardCaptureManager.CaptureStack(
                        backends = null,
                        access =
                            ClipboardAccessCoordinator(
                                backends = listOf(shizuku, foreground),
                                nowEpochMillis = { 7L },
                            ),
                        releaseOverlayFocus = { releaseOverlayCount += 1 },
                    )
                },
                onCapture = { change: ClipboardChange -> captured += change.text },
                scope = scope,
                rebuildDebounceMs = rebuildDebounceMs,
            )
    }

    @Test
    fun `ensureStarted builds the stack once and is idempotent`() {
        val harness = Harness()

        harness.manager.ensureStarted()
        harness.manager.ensureStarted()

        assertTrue(harness.manager.isStarted())
        assertEquals(1, harness.buildCount)
        assertEquals(1, harness.callLog.count { it == "SHIZUKU_EVENT.start" })
    }

    @Test
    fun `clipboard changes reach the capture sink`() {
        val harness = Harness()
        harness.manager.ensureStarted()

        harness.currentBackend?.emit("user copy", "hash-1")

        assertEquals(listOf("user copy"), harness.captured)
    }

    @Test
    fun `losing activity visibility flips the flag and releases overlay focus`() {
        val harness = Harness()
        harness.manager.ensureStarted()

        harness.manager.setActivityVisible(true)
        assertTrue(harness.lastIsVisible?.invoke() == true)

        harness.manager.setActivityVisible(false)
        assertFalse(harness.lastIsVisible?.invoke() == true)
        assertEquals(1, harness.releaseOverlayCount)
    }

    @Test
    fun `read mode only change retargets the live coordinator without rebuild`() {
        val harness = Harness()
        harness.manager.ensureStarted()

        harness.manager.applyChoices(
            WizardChoices().copy(preferredReadMode = ClipboardReadMode.FOREGROUND_ONLY),
        )

        assertEquals(1, harness.buildCount)
        assertEquals(
            ClipboardReadMode.FOREGROUND_ONLY,
            harness.manager
                .access()
                ?.state
                ?.requestedReadMode,
        )
    }

    @Test
    fun `structural change stops the old stack and rebuilds`() {
        val harness = Harness()
        harness.manager.ensureStarted()
        val oldChoices = WizardChoices()

        harness.manager.applyChoices(
            oldChoices.copy(pollingIntervalMs = oldChoices.pollingIntervalMs + 500),
        )

        assertEquals(2, harness.buildCount)
        assertTrue(harness.callLog.contains("SHIZUKU_EVENT.stop"))
        assertTrue(harness.releaseOverlayCount >= 1)
        assertTrue(harness.manager.isStarted())
    }

    @Test
    fun `identical choices are a no-op`() {
        val harness = Harness()
        harness.manager.ensureStarted()

        harness.manager.applyChoices(WizardChoices())

        assertEquals(1, harness.buildCount)
    }

    @Test
    fun `mode change during the debounce window folds into the rebuild`() {
        val scheduler = TestCoroutineScheduler()
        val harness = Harness(
            rebuildDebounceMs = 1000L,
            scope = CoroutineScope(StandardTestDispatcher(scheduler)),
        )
        harness.manager.ensureStarted()
        scheduler.runCurrent()
        val base = WizardChoices()

        harness.manager.applyChoices(
            base.copy(pollingIntervalMs = base.pollingIntervalMs + 500),
        )
        harness.manager.applyChoices(
            base.copy(
                pollingIntervalMs = base.pollingIntervalMs + 500,
                preferredReadMode = ClipboardReadMode.FOREGROUND_ONLY,
            ),
        )
        assertEquals(1, harness.buildCount)

        scheduler.advanceTimeBy(1001L)
        scheduler.runCurrent()

        assertEquals(2, harness.buildCount)
        assertEquals(
            ClipboardReadMode.FOREGROUND_ONLY,
            harness.manager.access()?.state?.requestedReadMode,
        )
    }
}
