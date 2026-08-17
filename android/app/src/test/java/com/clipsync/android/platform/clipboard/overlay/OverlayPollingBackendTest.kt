package com.clipsync.android.platform.clipboard.overlay

import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ContentHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPollingBackendTest {
    @Test
    fun `mode is overlay polling`() {
        val backend = backend()
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, backend.mode)
    }

    @Test
    fun `permission missing probe is NEEDS_USER_ACTION`() {
        val platform = FakeOverlayPlatform()
        platform.overlaysAllowed = false
        val backend = backend(platform = platform)

        val report = backend.probe()

        assertEquals(ClipboardReadMode.OVERLAY_POLLING, report.readMode)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, report.readState)
        assertEquals(OverlayFocusController.ERROR_PERMISSION_MISSING, report.errorCode)
        assertTrue(report.authorizations.any { it.name == AUTH_OVERLAY && !it.granted })
        assertTrue(report.readState != CapabilityState.READY)
    }

    @Test
    fun `permission missing readText is OVERLAY_PERMISSION_MISSING`() {
        val platform = FakeOverlayPlatform()
        platform.overlaysAllowed = false
        val backend = backend(platform = platform)

        assertEquals(
            ClipboardReadResult.Failure(OverlayFocusController.ERROR_PERMISSION_MISSING),
            backend.readText(),
        )
    }

    @Test
    fun `touchable-required ROM probe is UNAVAILABLE and never drops touch flag`() {
        val platform = FakeOverlayPlatform()
        platform.touchableRequired = true
        platform.clip = OverlayClipRead.Text("secret")
        val backend = backend(platform = platform)

        val report = backend.probe()

        assertEquals(CapabilityState.UNAVAILABLE, report.readState)
        assertEquals(OverlayFocusController.ERROR_TOUCHABLE_REQUIRED, report.errorCode)
        backend.readText()
        assertTrue(platform.neverDroppedTouchable())
        assertTrue(platform.snapshotsWithoutTouchable().isEmpty())
    }

    @Test
    fun `probe is READY only with overlay permission and interactive screen`() {
        val platform = FakeOverlayPlatform()
        var interactive = true
        val backend = backend(platform = platform, canPollNow = { interactive })

        val ready = backend.probe()
        assertEquals(CapabilityState.READY, ready.readState)
        assertNull(ready.errorCode)
        assertTrue(ready.authorizations.any { it.name == AUTH_OVERLAY && it.granted })

        interactive = false
        val paused = backend.probe()
        assertEquals(CapabilityState.DEGRADED, paused.readState)
        assertEquals(OverlayPollingBackend.ERROR_SCREEN_NOT_INTERACTIVE, paused.errorCode)
        assertTrue(paused.readState != CapabilityState.READY)
    }

    @Test
    fun `poll emits only on hash change`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("hello")
        val scheduler = ManualOverlayPollScheduler()
        val changes = mutableListOf<ClipboardChange>()
        val backend = backend(
            platform = platform,
            scheduler = scheduler,
            hasher = ContentHasher { "hash:$it" },
            nowEpochMillis = { 42L },
        )

        backend.start { changes += it }
        assertEquals(emptyList<ClipboardChange>(), changes)

        scheduler.fire()
        assertEquals(emptyList<ClipboardChange>(), changes)

        platform.clip = OverlayClipRead.Text("hello")
        scheduler.fire()
        assertEquals(emptyList<ClipboardChange>(), changes)

        platform.clip = OverlayClipRead.Text("world")
        scheduler.fire()

        assertEquals(1, changes.size)
        assertEquals("world", changes[0].text)
        assertEquals("hash:world", changes[0].contentHash)
        assertEquals(42L, changes[0].observedAtEpochMillis)
    }

    @Test
    fun `poll is paused when canPollNow is false`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("first")
        var interactive = true
        val scheduler = ManualOverlayPollScheduler()
        val changes = mutableListOf<String>()
        val backend = backend(
            platform = platform,
            canPollNow = { interactive },
            scheduler = scheduler,
            hasher = ContentHasher { "hash:$it" },
        )

        backend.start { changes += it.text }
        val readsAfterStart = platform.readCount

        interactive = false
        platform.clip = OverlayClipRead.Text("while-off")
        scheduler.fire()

        assertEquals(emptyList<String>(), changes)
        assertEquals(readsAfterStart, platform.readCount)
        assertIdleOrReleased(platform)

        interactive = true
        scheduler.fire()
        assertEquals(listOf("while-off"), changes)
    }

    @Test
    fun `poll interval is clamped to 500 through 2000 ms`() {
        val low = backend(pollIntervalMillis = 100L, scheduler = ManualOverlayPollScheduler())
        val high = backend(pollIntervalMillis = 9_000L, scheduler = ManualOverlayPollScheduler())
        val midScheduler = ManualOverlayPollScheduler()
        val mid = backend(pollIntervalMillis = 900L, scheduler = midScheduler)

        assertEquals(500L, low.pollIntervalMillis)
        assertEquals(2_000L, high.pollIntervalMillis)
        assertEquals(900L, mid.pollIntervalMillis)

        mid.start { }
        assertEquals(900L, midScheduler.lastIntervalMillis)
    }

    @Test
    fun `health is stopped until start then degraded when screen is off`() {
        var interactive = true
        val backend = backend(
            canPollNow = { interactive },
            nowEpochMillis = { 11L },
        )

        assertEquals(BackendHealthState.STOPPED, backend.health().state)

        backend.start { }
        assertEquals(BackendHealthState.HEALTHY, backend.health().state)

        interactive = false
        val paused = backend.health()
        assertEquals(BackendHealthState.DEGRADED, paused.state)
        assertEquals(OverlayPollingBackend.ERROR_SCREEN_NOT_INTERACTIVE, paused.errorCode)
        assertEquals(11L, paused.checkedAtEpochMillis)
    }

    @Test
    fun `stop unregisters scheduler and releases focus`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("held")
        val scheduler = ManualOverlayPollScheduler()
        val backend = backend(platform = platform, scheduler = scheduler)
        val changes = mutableListOf<String>()

        backend.start { changes += it.text }
        backend.stop()
        platform.clip = OverlayClipRead.Text("after-stop")
        scheduler.fire()

        assertFalse(scheduler.started)
        assertEquals(1, scheduler.stopCount)
        assertEquals(emptyList<String>(), changes)
        assertIdleOrReleased(platform)
    }

    private fun backend(
        platform: FakeOverlayPlatform = FakeOverlayPlatform(),
        canPollNow: () -> Boolean = { true },
        pollIntervalMillis: Long = OverlayPollingBackend.DEFAULT_POLL_INTERVAL_MS,
        scheduler: OverlayPollScheduler = ManualOverlayPollScheduler(),
        hasher: ContentHasher = ContentHasher { "hash:$it" },
        nowEpochMillis: () -> Long = { 1L },
    ): OverlayPollingBackend = OverlayPollingBackend(
        controller = OverlayFocusController(platform),
        canPollNow = canPollNow,
        pollIntervalMillis = pollIntervalMillis,
        scheduler = scheduler,
        hasher = hasher,
        nowEpochMillis = nowEpochMillis,
    )

    private fun assertIdleOrReleased(platform: FakeOverlayPlatform) {
        val window = platform.currentWindow()
        if (window != null) {
            assertEquals(
                OverlayFocusController.FLAG_NOT_FOCUSABLE or OverlayFocusController.FLAG_NOT_TOUCHABLE,
                window.flags,
            )
        }
        assertTrue(platform.neverDroppedTouchable())
    }

    companion object {
        private const val AUTH_OVERLAY = "system_alert_window"
    }
}
