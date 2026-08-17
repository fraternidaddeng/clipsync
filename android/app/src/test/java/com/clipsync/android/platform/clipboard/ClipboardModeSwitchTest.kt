package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardModeSwitchTest {
    @Test
    fun `mode switch runs stop, release focus, hash refresh, start, then epoch bump`() {
        val calls = mutableListOf<String>()
        val store = KeyValueClipboardCapabilityStore(InMemoryCapabilityKeyValueStore())
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success("old"),
            callLog = calls,
        )
        val overlay = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            readResult = ClipboardReadResult.Success("same-clip"),
            callLog = calls,
        )
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, overlay),
            hasher = ContentHasher { "hash:$it" },
            capabilityStore = store,
            releaseFocusResource = { calls += "releaseFocus" },
        )
        coordinator.start { }
        val epochAfterStart = coordinator.modeEpoch
        assertEquals(1L, epochAfterStart)
        calls.clear()

        coordinator.requestMode(ClipboardReadMode.OVERLAY_POLLING)

        assertEquals(
            listOf(
                "OVERLAY_POLLING.probe",
                "SHIZUKU_EVENT.stop",
                "releaseFocus",
                "OVERLAY_POLLING.read",
                "OVERLAY_POLLING.start",
            ),
            calls,
        )
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, coordinator.state.activeReadMode)
        assertEquals(epochAfterStart + 1L, coordinator.modeEpoch)
        assertEquals(epochAfterStart + 1L, store.loadRead()?.modeEpoch)
    }

    @Test
    fun `hash baseline refresh after switch suppresses the same clipboard text`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success("same-clip"),
        )
        val overlay = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            readResult = ClipboardReadResult.Success("same-clip"),
        )
        val emitted = mutableListOf<String>()
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, overlay),
            hasher = ContentHasher { "hash:$it" },
        )
        coordinator.start { emitted += it.text }

        coordinator.requestMode(ClipboardReadMode.OVERLAY_POLLING)
        overlay.emit("same-clip", "hash:same-clip")
        overlay.emit("next-clip", "hash:next-clip")

        assertEquals(listOf("next-clip"), emitted)
    }

    @Test
    fun `start failure rolls back to previous good mode without bumping epoch`() {
        val shizuku = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.SHIZUKU_EVENT)
        val overlay = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.OVERLAY_POLLING).apply {
            onStart = { error("start failed") }
        }
        val coordinator = ClipboardAccessCoordinator(backends = listOf(shizuku, overlay))
        coordinator.start { }
        val epoch = coordinator.modeEpoch

        val state = coordinator.requestMode(ClipboardReadMode.OVERLAY_POLLING)

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertEquals(epoch, coordinator.modeEpoch)
        assertEquals("CLIPBOARD_MODE_SWITCH_FAILED", state.lastErrorCode)
    }

    @Test
    fun `hash refresh failure rolls back to previous good mode without bumping epoch`() {
        val shizuku = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.SHIZUKU_EVENT)
        val overlay = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.OVERLAY_POLLING).apply {
            onRead = { error("hash refresh failed") }
        }
        val coordinator = ClipboardAccessCoordinator(backends = listOf(shizuku, overlay))
        coordinator.start { }
        val epoch = coordinator.modeEpoch

        val state = coordinator.requestMode(ClipboardReadMode.OVERLAY_POLLING)

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertEquals(epoch, coordinator.modeEpoch)
        assertEquals("CLIPBOARD_MODE_SWITCH_FAILED", state.lastErrorCode)
    }

    @Test
    fun `stop failure keeps previous mode and does not bump epoch`() {
        val shizuku = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.SHIZUKU_EVENT).apply {
            onStop = { error("stop failed") }
        }
        val overlay = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.OVERLAY_POLLING)
        val coordinator = ClipboardAccessCoordinator(backends = listOf(shizuku, overlay))
        coordinator.start { }
        val epoch = coordinator.modeEpoch

        val state = coordinator.requestMode(ClipboardReadMode.OVERLAY_POLLING)

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertEquals(epoch, coordinator.modeEpoch)
        assertEquals("CLIPBOARD_MODE_SWITCH_FAILED", state.lastErrorCode)
    }

    @Test
    fun `release focus failure rolls back to previous good mode without bumping epoch`() {
        val shizuku = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.SHIZUKU_EVENT)
        val overlay = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.OVERLAY_POLLING)
        var releaseAttempts = 0
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, overlay),
            releaseFocusResource = {
                releaseAttempts += 1
                // First start also releases focus; fail only on the subsequent switch.
                if (releaseAttempts == 2) error("focus release failed")
            },
        )
        coordinator.start { }
        val epoch = coordinator.modeEpoch

        val state = coordinator.requestMode(ClipboardReadMode.OVERLAY_POLLING)

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertEquals(epoch, coordinator.modeEpoch)
        assertEquals("CLIPBOARD_MODE_SWITCH_FAILED", state.lastErrorCode)
        assertTrue(releaseAttempts >= 1)
    }

    @Test
    fun `rollback target restart failure parks on foreground only without bumping epoch`() {
        var shizukuStarts = 0
        val shizuku = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.SHIZUKU_EVENT).apply {
            onStart = {
                shizukuStarts += 1
                if (shizukuStarts > 1) error("restart failed")
            }
        }
        val overlay = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.OVERLAY_POLLING).apply {
            onStart = { error("overlay start failed") }
        }
        val foreground = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, overlay, foreground),
        )
        coordinator.start { }
        val epoch = coordinator.modeEpoch

        val state = coordinator.requestMode(ClipboardReadMode.OVERLAY_POLLING)

        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, state.activeReadMode)
        assertEquals(epoch, coordinator.modeEpoch)
        assertEquals("CLIPBOARD_MODE_SWITCH_FAILED", state.lastErrorCode)
    }
}
