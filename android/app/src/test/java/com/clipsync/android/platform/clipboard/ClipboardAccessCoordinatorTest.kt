package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardAccessCoordinatorTest {
    @Test
    fun `selects highest ready backend from requested mode`() {
        val calls = mutableListOf<String>()
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.UNAVAILABLE,
                errorCode = "SHIZUKU_NOT_RUNNING",
            ),
            callLog = calls,
        )
        val adb = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.ADB_LOG_OVERLAY,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.ADB_LOG_OVERLAY,
                state = CapabilityState.DEGRADED,
                errorCode = "ADB_SIGNAL_UNVERIFIED",
            ),
            callLog = calls,
        )
        val overlay = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            callLog = calls,
        )

        val state = ClipboardAccessCoordinator(
            backends = listOf(shizuku, adb, overlay),
            nowEpochMillis = { 42L },
        ).start { }

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.requestedReadMode)
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, state.activeReadMode)
        assertEquals(
            listOf(
                "SHIZUKU_EVENT.probe",
                "ADB_LOG_OVERLAY.probe",
                "OVERLAY_POLLING.probe",
                "OVERLAY_POLLING.read",
                "OVERLAY_POLLING.start",
            ),
            calls,
        )
    }

    @Test
    fun `switch stops old backend then refreshes hash before starting new listener`() {
        val calls = mutableListOf<String>()
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success("old"),
            callLog = calls,
        )
        val foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            readResult = ClipboardReadResult.Success("baseline"),
            callLog = calls,
        )
        val emitted = mutableListOf<String>()
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, foreground),
            hasher = ContentHasher { "hash:$it" },
        )
        coordinator.start { emitted += it.text }
        calls.clear()

        coordinator.requestMode(ClipboardReadMode.FOREGROUND_ONLY)
        foreground.emit("baseline", "hash:baseline")
        foreground.emit("next", "hash:next")

        assertEquals(
            listOf(
                "FOREGROUND_ONLY.probe",
                "SHIZUKU_EVENT.stop",
                "FOREGROUND_ONLY.read",
                "FOREGROUND_ONLY.start",
            ),
            calls,
        )
        assertEquals(listOf("next"), emitted)
    }

    @Test
    fun `failed active backend falls back when allowed`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            backendHealth = BackendHealth(
                state = BackendHealthState.FAILED,
                checkedAtEpochMillis = 50L,
                errorCode = "SHIZUKU_DISCONNECTED",
            ),
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku, foreground))
        coordinator.start { }

        val state = coordinator.checkHealth()

        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, state.activeReadMode)
        assertNull(state.lastErrorCode)
    }

    @Test
    fun `failed active backend remains selected when fallback is disabled`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            backendHealth = BackendHealth(
                state = BackendHealthState.FAILED,
                checkedAtEpochMillis = 50L,
                errorCode = "SHIZUKU_DISCONNECTED",
            ),
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, foreground),
            autoFallbackAllowed = false,
        )
        coordinator.start { }

        val state = coordinator.checkHealth()

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertEquals("SHIZUKU_DISCONNECTED", state.lastErrorCode)
        assertEquals(50L, state.lastHealthAtEpochMillis)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, coordinator.lastReadState)
    }

    @Test
    fun `needs user action probe is reported but never auto selected when fallback is allowed`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.NEEDS_USER_ACTION,
                errorCode = "SHIZUKU_NOT_AUTHORIZED",
            ),
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku, foreground))

        val state = coordinator.start { }

        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, state.activeReadMode)
        assertTrue(
            coordinator.lastSelectionReports.any { report ->
                report.readMode == ClipboardReadMode.SHIZUKU_EVENT &&
                    report.readState == CapabilityState.NEEDS_USER_ACTION &&
                    report.errorCode == "SHIZUKU_NOT_AUTHORIZED"
            },
        )
        assertTrue(
            coordinator.lastSelectionReports.none { report ->
                report.readMode == ClipboardReadMode.SHIZUKU_EVENT &&
                    report.readState == CapabilityState.READY
            },
        )
    }

    @Test
    fun `needs user action parks without selecting when fallback is disabled`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.NEEDS_USER_ACTION,
                errorCode = "SHIZUKU_NOT_RUNNING",
            ),
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, foreground),
            autoFallbackAllowed = false,
        )

        val state = coordinator.start { }

        assertNull(state.activeReadMode)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, coordinator.lastReadState)
        assertEquals("SHIZUKU_NOT_RUNNING", state.lastErrorCode)
        assertTrue(
            coordinator.lastSelectionReports.any { it.readState == CapabilityState.NEEDS_USER_ACTION },
        )
    }

    @Test
    fun `needs user action is reported distinctly from unavailable`() {
        val needsAction = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.NEEDS_USER_ACTION,
                errorCode = "SHIZUKU_NOT_INSTALLED",
            ),
        )
        val unavailable = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.UNAVAILABLE,
                errorCode = "SHIZUKU_BINDER_DEAD",
            ),
        )

        val parked = ClipboardAccessCoordinator(
            backends = listOf(needsAction, FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)),
            autoFallbackAllowed = false,
        )
        parked.start { }
        assertEquals(CapabilityState.NEEDS_USER_ACTION, parked.lastReadState)
        assertEquals("SHIZUKU_NOT_INSTALLED", parked.state.lastErrorCode)

        val missing = ClipboardAccessCoordinator(
            backends = listOf(unavailable, FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)),
            autoFallbackAllowed = false,
        )
        missing.start { }
        assertEquals(CapabilityState.UNAVAILABLE, missing.lastReadState)
        assertEquals("SHIZUKU_BINDER_DEAD", missing.state.lastErrorCode)
    }

    @Test
    fun `health tick with needs user action falls back only when allowed`() {
        val shizuku = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.SHIZUKU_EVENT)
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku, foreground))
        coordinator.start { }
        shizuku.report = FakeBackgroundClipboardBackend.capabilityReport(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            state = CapabilityState.NEEDS_USER_ACTION,
            errorCode = "SHIZUKU_NOT_AUTHORIZED",
        )
        shizuku.backendHealth = BackendHealth(
            state = BackendHealthState.FAILED,
            checkedAtEpochMillis = 80L,
            errorCode = "SHIZUKU_NOT_AUTHORIZED",
        )

        val state = coordinator.checkHealth()

        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, state.activeReadMode)
        assertEquals(CapabilityState.READY, coordinator.lastReadState)
    }

    @Test
    fun `health tick parks at needs user action when fallback is disabled`() {
        val shizuku = FakeBackgroundClipboardBackend(mode = ClipboardReadMode.SHIZUKU_EVENT)
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku, foreground),
            autoFallbackAllowed = false,
        )
        coordinator.start { }
        shizuku.report = FakeBackgroundClipboardBackend.capabilityReport(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            state = CapabilityState.NEEDS_USER_ACTION,
            errorCode = "SHIZUKU_NOT_AUTHORIZED",
        )
        shizuku.backendHealth = BackendHealth(
            state = BackendHealthState.FAILED,
            checkedAtEpochMillis = 80L,
            errorCode = "SHIZUKU_NOT_AUTHORIZED",
        )

        val state = coordinator.checkHealth()

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, coordinator.lastReadState)
        assertEquals("SHIZUKU_NOT_AUTHORIZED", state.lastErrorCode)
        assertEquals(80L, state.lastHealthAtEpochMillis)
    }

    @Test
    fun `stop invokes the overlay release hook after stopping the backend`() {
        val calls = mutableListOf<String>()
        val overlay = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            callLog = calls,
        )
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(overlay),
            releaseFocusResource = { calls += "detach" },
        )
        coordinator.start { }
        calls.clear()

        coordinator.stop()

        assertEquals(listOf("OVERLAY_POLLING.stop", "detach"), calls)
        assertEquals(null, coordinator.state.activeReadMode)
    }
}
