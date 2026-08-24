package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                errorCode = "PRIVILEGED_CHANNEL_OFFLINE",
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
    fun `privileged backend that reports ready becomes active and its reads flow through`() {
        val calls = mutableListOf<String>()
        // Simulates the stage-5.3 privileged backend after device verification:
        // probe READY, a baseline clip present, change events via the callback.
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success("baseline"),
            callLog = calls,
        )
        val foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            callLog = calls,
        )
        val emitted = mutableListOf<String>()
        val state = ClipboardAccessCoordinator(
            backends = listOf(shizuku, foreground),
            hasher = ContentHasher { "hash:$it" },
        ).start { emitted += it.text }

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertNull(state.lastErrorCode)
        // Selection stops at the first READY backend; nothing downstream is touched.
        assertEquals(
            listOf("SHIZUKU_EVENT.probe", "SHIZUKU_EVENT.read", "SHIZUKU_EVENT.start"),
            calls,
        )

        // The clip present at start is a baseline, not a new change; later copies flow.
        shizuku.emit("baseline", "hash:baseline")
        shizuku.emit("copied later", "hash:copied later")
        assertEquals(listOf("copied later"), emitted)
    }

    @Test
    fun `privileged mode activates on request once its backend turns ready`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.UNAVAILABLE,
                errorCode = "PRIVILEGED_PERMISSION_DENIED",
            ),
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku, foreground))
        coordinator.start { }
        // Denied privileged backend is skipped; the fallback selection succeeds,
        // so no error code survives (codes only persist when nothing starts).
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, coordinator.state.activeReadMode)
        assertNull(coordinator.state.lastErrorCode)

        // Authorization granted and reads verified: the next probe reports READY.
        shizuku.report = FakeBackgroundClipboardBackend.capabilityReport(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            state = CapabilityState.READY,
        )
        val state = coordinator.requestMode(ClipboardReadMode.SHIZUKU_EVENT)

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertNull(state.lastErrorCode)
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
    fun `probeAll reports every backend in ladder order without starting anything`() {
        val calls = mutableListOf<String>()
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.UNAVAILABLE,
                errorCode = "PRIVILEGED_CHANNEL_MISSING",
            ),
            callLog = calls,
        )
        val overlay = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            callLog = calls,
        )
        val foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            callLog = calls,
        )
        val coordinator = ClipboardAccessCoordinator(listOf(foreground, shizuku, overlay))

        val reports = coordinator.probeAll()

        assertEquals(
            listOf(
                ClipboardReadMode.SHIZUKU_EVENT,
                ClipboardReadMode.OVERLAY_POLLING,
                ClipboardReadMode.FOREGROUND_ONLY,
            ),
            reports.map { it.readMode },
        )
        assertEquals("PRIVILEGED_CHANNEL_MISSING", reports.first().errorCode)
        assertEquals(
            listOf("SHIZUKU_EVENT.probe", "OVERLAY_POLLING.probe", "FOREGROUND_ONLY.probe"),
            calls,
        )
        assertNull(coordinator.state.activeReadMode)
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
    }

    @Test
    fun `probe returns null when no backends are registered`() {
        assertNull(ClipboardAccessCoordinator(emptyList()).probe())
    }

    @Test
    fun `probe returns the most capable report without starting anything`() {
        val calls = mutableListOf<String>()
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.UNAVAILABLE,
                errorCode = "PRIVILEGED_CHANNEL_OFFLINE",
            ),
            callLog = calls,
        )
        val overlay = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.OVERLAY_POLLING,
                state = CapabilityState.DEGRADED,
            ),
            callLog = calls,
        )
        val foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            callLog = calls,
        )

        val report = ClipboardAccessCoordinator(listOf(shizuku, overlay, foreground)).probe()

        // READY beats the earlier DEGRADED and UNAVAILABLE reports.
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, report?.readMode)
        assertEquals(CapabilityState.READY, report?.readState)
        assertEquals(
            listOf("SHIZUKU_EVENT.probe", "OVERLAY_POLLING.probe", "FOREGROUND_ONLY.probe"),
            calls,
        )
    }

    @Test
    fun `probe stops at the first ready backend in fallback order`() {
        val calls = mutableListOf<String>()
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            callLog = calls,
        )
        val foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            callLog = calls,
        )

        val report = ClipboardAccessCoordinator(listOf(shizuku, foreground)).probe()

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, report?.readMode)
        assertEquals(listOf("SHIZUKU_EVENT.probe"), calls)
    }

    @Test
    fun `probe prefers degraded over unavailable when nothing is ready`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.DEGRADED,
            ),
        )
        val foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            report = FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.FOREGROUND_ONLY,
                state = CapabilityState.UNAVAILABLE,
            ),
        )

        val report = ClipboardAccessCoordinator(listOf(shizuku, foreground)).probe()

        assertEquals(CapabilityState.DEGRADED, report?.readState)
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, report?.readMode)
    }
}
