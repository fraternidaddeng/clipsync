package com.clipsync.android.platform.clipboard

import com.clipsync.android.platform.clipboard.overlay.FakeOverlayPlatform
import com.clipsync.android.platform.clipboard.overlay.OverlayClipRead
import com.clipsync.android.platform.clipboard.overlay.OverlayFocusController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundClipboardBackendsTest {
    @Test
    fun `factory returns unique modes in FALLBACK_ORDER`() {
        val assembly = assembly()
        assertEquals(BackgroundClipboardBackends.FALLBACK_ORDER, assembly.backends.map { it.mode })
        assertEquals(assembly.backends.size, assembly.backends.map { it.mode }.toSet().size)
        assertEquals(
            listOf(
                ClipboardReadMode.SHIZUKU_EVENT,
                ClipboardReadMode.ADB_LOG_OVERLAY,
                ClipboardReadMode.OVERLAY_POLLING,
                ClipboardReadMode.FOREGROUND_ONLY,
            ),
            assembly.backends.map { it.mode },
        )
    }

    @Test
    fun `defaults stay foreground only with auto fallback off`() {
        val assembly = assembly()
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, assembly.requestedReadMode)
        assertFalse(assembly.autoFallbackAllowed)
        val coordinator = assembly.coordinator()
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, coordinator.state.requestedReadMode)
        assertFalse(coordinator.state.autoFallbackAllowed)
        assertNull(coordinator.state.activeReadMode)
    }

    @Test
    fun `constructing the factory and coordinator does not probe or start backends`() {
        val calls = mutableListOf<String>()
        val assembly = assembly(callLog = calls)
        assembly.coordinator()
        assertTrue(calls.isEmpty())
        assertNull(assembly.coordinator().state.activeReadMode)
    }

    @Test
    fun `coordinator uses overlay release hook on start`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("held")
        val controller = OverlayFocusController(platform)
        controller.readText()
        val idleBefore = platform.eventLog.count { it == FakeOverlayPlatform.EVENT_IDLE_FLAGS }

        val assembly = assembly(
            overlayController = controller,
            requestedReadMode = ClipboardReadMode.SHIZUKU_EVENT,
        )
        assembly.coordinator().start { }

        val idleAfter = platform.eventLog.count { it == FakeOverlayPlatform.EVENT_IDLE_FLAGS }
        assertTrue(idleAfter > idleBefore)
        val window = platform.currentWindow()
        assertNotNull(window)
        assertEquals(
            OverlayFocusController.FLAG_NOT_FOCUSABLE or OverlayFocusController.FLAG_NOT_TOUCHABLE,
            window!!.flags,
        )
        assertTrue(platform.neverDroppedTouchable())
    }

    @Test
    fun `missing ClipboardManager still returns a coordinator with remaining backends`() {
        val calls = mutableListOf<String>()
        val assembly = BackgroundClipboardBackends.build(
            overlayController = OverlayFocusController(FakeOverlayPlatform()),
            shizuku = FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                callLog = calls,
            ),
            adbLog = FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.ADB_LOG_OVERLAY,
                callLog = calls,
            ),
            overlayPolling = FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.OVERLAY_POLLING,
                callLog = calls,
            ),
            foreground = null,
        )
        assertEquals(3, assembly.backends.size)
        assertNull(assembly.foreground)
        assertEquals(
            BackgroundClipboardBackends.FALLBACK_ORDER.filter { it != ClipboardReadMode.FOREGROUND_ONLY },
            assembly.backends.map { it.mode },
        )
        val coordinator = assembly.coordinator()
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, coordinator.state.requestedReadMode)
        val state = coordinator.start { }
        assertNull(state.activeReadMode)
        assertTrue(calls.none { it.endsWith(".start") })
    }

    @Test
    fun `selected eligible backend is the requested mode when present`() {
        val assembly = assembly()
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, assembly.selectedEligibleBackend()?.mode)
        assertEquals(
            ClipboardReadMode.SHIZUKU_EVENT,
            assembly.selectedEligibleBackend(ClipboardReadMode.SHIZUKU_EVENT)?.mode,
        )
    }

    private fun assembly(
        overlayController: OverlayFocusController = OverlayFocusController(FakeOverlayPlatform()),
        requestedReadMode: ClipboardReadMode = ClipboardReadMode.FOREGROUND_ONLY,
        autoFallbackAllowed: Boolean = false,
        callLog: MutableList<String> = mutableListOf(),
    ): BackgroundClipboardBackends = BackgroundClipboardBackends.build(
        overlayController = overlayController,
        shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            callLog = callLog,
        ),
        adbLog = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.ADB_LOG_OVERLAY,
            callLog = callLog,
        ),
        overlayPolling = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            callLog = callLog,
        ),
        foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            callLog = callLog,
        ),
        requestedReadMode = requestedReadMode,
        autoFallbackAllowed = autoFallbackAllowed,
    )
}
