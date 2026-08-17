package com.clipsync.android.platform.clipboard.overlay

import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks overlay window invariants: always FLAG_NOT_TOUCHABLE, 1x1 transparent,
 * detach on stop / revoke, and permission revoke leaves READY in one probe.
 */
class OverlayLifecycleInvariantTest {
    @Test
    fun `stop detaches the window and never drops FLAG_NOT_TOUCHABLE`() {
        val platform = FakeOverlayPlatform()
        val scheduler = ManualOverlayPollScheduler()
        val backend = OverlayPollingBackend(
            controller = OverlayFocusController(platform),
            canPollNow = { true },
            scheduler = scheduler,
        )

        backend.start { }
        backend.stop()

        assertEquals(null, platform.currentWindow())
        assertTrue(platform.detachCount >= 1)
        assertTrue(platform.neverDroppedTouchable())
        assertTrue(platform.snapshotsWithoutTouchable().isEmpty())
    }

    @Test
    fun `every attached spec is 1x1 transparent and not touchable`() {
        val platform = FakeOverlayPlatform()
        val controller = OverlayFocusController(platform)

        controller.readText()
        controller.releaseFocus()

        assertTrue(platform.windowHistory.isNotEmpty())
        for (spec in platform.windowHistory) {
            assertEquals(1, spec.widthPx)
            assertEquals(1, spec.heightPx)
            assertEquals(0f, spec.alpha, 0f)
            assertTrue(spec.flags and OverlayFocusController.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(OverlayFocusController.TYPE_APPLICATION_OVERLAY, spec.type)
        }
    }

    @Test
    fun `revoking overlay permission leaves READY within one probe and health check`() {
        val platform = FakeOverlayPlatform()
        platform.overlaysAllowed = true
        val backend = OverlayPollingBackend(
            controller = OverlayFocusController(platform),
            canPollNow = { true },
            scheduler = ManualOverlayPollScheduler(),
        )
        backend.start { }
        assertEquals(CapabilityState.READY, backend.probe().readState)
        assertEquals(BackendHealthState.HEALTHY, backend.health().state)

        platform.overlaysAllowed = false
        val report = backend.probe()
        assertTrue(report.readState != CapabilityState.READY)
        assertEquals(OverlayFocusController.ERROR_PERMISSION_MISSING, report.errorCode)

        val health = backend.health()
        assertTrue(health.state != BackendHealthState.HEALTHY)
        assertEquals(OverlayFocusController.ERROR_PERMISSION_MISSING, health.errorCode)
        assertEquals(null, platform.currentWindow())
        assertTrue(platform.detachCount >= 1)
    }

    @Test
    fun `switching away from overlay polling detaches the window`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("held")
        val controller = OverlayFocusController(platform)
        val overlay = OverlayPollingBackend(
            controller = controller,
            canPollNow = { true },
            scheduler = ManualOverlayPollScheduler(),
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(overlay, foreground),
            requestedReadMode = ClipboardReadMode.OVERLAY_POLLING,
            releaseFocusResource = controller::detach,
        )
        coordinator.start { }
        coordinator.requestMode(ClipboardReadMode.FOREGROUND_ONLY)

        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, coordinator.state.activeReadMode)
        assertEquals(null, platform.currentWindow())
        assertTrue(platform.detachCount >= 1)
        assertTrue(platform.neverDroppedTouchable())
    }
}
