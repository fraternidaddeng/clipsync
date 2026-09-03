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
                errorCode = "PRIV_HOST_DISCONNECTED",
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
                errorCode = "PRIV_HOST_DISCONNECTED",
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
        assertEquals("PRIV_HOST_DISCONNECTED", state.lastErrorCode)
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
    fun `active read mode changes are announced - start, request, fallback, stop`() {
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            backendHealth = BackendHealth(
                state = BackendHealthState.FAILED,
                checkedAtEpochMillis = 50L,
                errorCode = "PRIV_HOST_DISCONNECTED",
            ),
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku, foreground))
        val announced = mutableListOf<ClipboardReadMode?>()
        coordinator.onActiveReadModeChanged = { announced += it }

        // Start selects the privileged backend and announces it once.
        coordinator.start { }
        assertEquals(listOf<ClipboardReadMode?>(ClipboardReadMode.SHIZUKU_EVENT), announced)

        // Re-selecting the same mode is not a change: the notification must not flicker.
        coordinator.requestMode(ClipboardReadMode.SHIZUKU_EVENT)
        assertEquals(1, announced.size)

        // The health-check fallback is a real switch — the resident notification
        // hears it when it happens (plan 5.5), not on the next periodic tick.
        coordinator.checkHealth()
        assertEquals(
            listOf(ClipboardReadMode.SHIZUKU_EVENT, ClipboardReadMode.FOREGROUND_ONLY),
            announced,
        )

        coordinator.stop()
        assertEquals(
            listOf(ClipboardReadMode.SHIZUKU_EVENT, ClipboardReadMode.FOREGROUND_ONLY, null),
            announced,
        )
    }

    // ---- recovery: climbing back up the ladder ---------------------------------------------

    private fun degradedLadder(
        calls: MutableList<String>,
        clock: () -> Long,
    ): Triple<ClipboardAccessCoordinator, FakeBackgroundClipboardBackend, FakeBackgroundClipboardBackend> {
        val shizuku =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                readResult = ClipboardReadResult.Success("baseline"),
                backendHealth =
                    BackendHealth(
                        state = BackendHealthState.FAILED,
                        checkedAtEpochMillis = 50L,
                        errorCode = "PRIV_HOST_USERSERVICE_DEAD",
                    ),
                callLog = calls,
            )
        val overlay =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.OVERLAY_POLLING,
                readResult = ClipboardReadResult.Success("baseline"),
                callLog = calls,
            )
        val coordinator =
            ClipboardAccessCoordinator(
                backends = listOf(shizuku, overlay),
                hasher = ContentHasher { "hash:$it" },
                nowEpochMillis = clock,
            )
        return Triple(coordinator, shizuku, overlay)
    }

    @Test
    fun `health fallback records the shortfall with its cause, code and time`() {
        var now = 1_000L
        val (coordinator, _, _) = degradedLadder(mutableListOf()) { now }
        coordinator.start { }
        assertNull(coordinator.state.shortfall)

        now = 2_000L
        val state = coordinator.checkHealth()

        assertEquals(ClipboardReadMode.OVERLAY_POLLING, state.activeReadMode)
        assertEquals(
            ReadRouteShortfall(
                cause = ReadRouteShortfallCause.HEALTH_FALLBACK,
                fromMode = ClipboardReadMode.SHIZUKU_EVENT,
                errorCode = "PRIV_HOST_USERSERVICE_DEAD",
                sinceEpochMillis = 2_000L,
            ),
            state.shortfall,
        )
        assertEquals(true, state.belowRequested)
    }

    @Test
    fun `a start that misses the preferred rung records a preferred-not-ready shortfall`() {
        val shizuku =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                report =
                    FakeBackgroundClipboardBackend.capabilityReport(
                        mode = ClipboardReadMode.SHIZUKU_EVENT,
                        state = CapabilityState.UNAVAILABLE,
                        errorCode = "PRIVILEGED_CHANNEL_OFFLINE",
                    ),
            )
        val overlay = FakeBackgroundClipboardBackend(ClipboardReadMode.OVERLAY_POLLING)
        val state = ClipboardAccessCoordinator(listOf(shizuku, overlay), nowEpochMillis = { 7L }).start { }

        assertEquals(
            ReadRouteShortfall(
                cause = ReadRouteShortfallCause.PREFERRED_NOT_READY,
                fromMode = ClipboardReadMode.SHIZUKU_EVENT,
                errorCode = "PRIVILEGED_CHANNEL_OFFLINE",
                sinceEpochMillis = 7L,
            ),
            state.shortfall,
        )
    }

    @Test
    fun `recovery climbs back once the preferred rung is ready and never double-captures`() {
        var now = 1_000L
        val calls = mutableListOf<String>()
        val (coordinator, shizuku, overlay) = degradedLadder(calls) { now }
        val emitted = mutableListOf<String>()
        val announced = mutableListOf<ClipboardReadMode?>()
        coordinator.onActiveReadModeChanged = { announced += it }
        coordinator.start { emitted += it.text }
        coordinator.checkHealth()
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, coordinator.state.activeReadMode)

        // The privileged host is still dead: the recovery probe leaves the ladder alone.
        shizuku.report =
            FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.UNAVAILABLE,
                errorCode = "PRIV_HOST_USERSERVICE_DEAD",
            )
        calls.clear()
        coordinator.tryRecover()
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, coordinator.state.activeReadMode)
        assertEquals(listOf("SHIZUKU_EVENT.probe"), calls)
        assertEquals(ReadRouteShortfallCause.HEALTH_FALLBACK, coordinator.state.shortfall?.cause)

        // The user restarted the host from the PC and the read test passed: READY again.
        shizuku.report =
            FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.READY,
            )
        shizuku.backendHealth = BackendHealth(BackendHealthState.HEALTHY, checkedAtEpochMillis = 3_000L)
        now = 3_000L
        calls.clear()
        val state = coordinator.tryRecover()

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertNull(state.shortfall)
        assertEquals(3_000L, state.lastRecoveryAtEpochMillis)
        // Same switch order as a fallback: probe, stop the old rung, refresh baseline, start new.
        assertEquals(
            listOf("SHIZUKU_EVENT.probe", "OVERLAY_POLLING.stop", "SHIZUKU_EVENT.read", "SHIZUKU_EVENT.start"),
            calls,
        )
        assertEquals(
            listOf(ClipboardReadMode.SHIZUKU_EVENT, ClipboardReadMode.OVERLAY_POLLING, ClipboardReadMode.SHIZUKU_EVENT),
            announced,
        )

        // Only the recovered backend feeds the listener; the stopped rung is deaf, and the
        // clip already on the clipboard at switch time is a baseline, not a new copy.
        overlay.emit("stale from the old rung", "hash:stale")
        shizuku.emit("baseline", "hash:baseline")
        shizuku.emit("fresh copy", "hash:fresh copy")
        assertEquals(listOf("fresh copy"), emitted)
    }

    @Test
    fun `recovery is a no-op at the preferred rung, when stopped, and below a not-ready preference`() {
        val calls = mutableListOf<String>()
        val shizuku = FakeBackgroundClipboardBackend(ClipboardReadMode.SHIZUKU_EVENT, callLog = calls)
        val overlay = FakeBackgroundClipboardBackend(ClipboardReadMode.OVERLAY_POLLING, callLog = calls)
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku, overlay))

        // Stopped: nothing to recover, nothing probed.
        coordinator.tryRecover()
        assertTrue(calls.isEmpty())
        assertNull(coordinator.state.activeReadMode)

        // At the preferred rung: no probe at all.
        coordinator.start { }
        calls.clear()
        coordinator.tryRecover()
        assertTrue(calls.isEmpty())
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, coordinator.state.activeReadMode)
    }

    @Test
    fun `recovery with nothing running re-runs the selection from the requested rung`() {
        val shizuku =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                report =
                    FakeBackgroundClipboardBackend.capabilityReport(
                        mode = ClipboardReadMode.SHIZUKU_EVENT,
                        state = CapabilityState.UNAVAILABLE,
                        errorCode = "PRIVILEGED_CHANNEL_OFFLINE",
                    ),
            )
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku))
        coordinator.start { }
        assertNull(coordinator.state.activeReadMode)

        shizuku.report =
            FakeBackgroundClipboardBackend.capabilityReport(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                state = CapabilityState.READY,
            )
        val state = coordinator.tryRecover()

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertNull(state.shortfall)
    }

    @Test
    fun `stop clears the shortfall and the states flow mirrors every change`() {
        var now = 1L
        val (coordinator, _, _) = degradedLadder(mutableListOf()) { now }
        coordinator.start { }
        now = 2L
        coordinator.checkHealth()
        assertEquals(coordinator.state, coordinator.states.value)
        assertEquals(true, coordinator.states.value.belowRequested)

        coordinator.stop()

        assertNull(coordinator.state.activeReadMode)
        assertNull(coordinator.state.shortfall)
        assertEquals(coordinator.state, coordinator.states.value)
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
