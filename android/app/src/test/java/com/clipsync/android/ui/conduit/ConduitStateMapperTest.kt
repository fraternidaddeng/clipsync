package com.clipsync.android.ui.conduit

import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConduitStateMapperTest {
    // ---- read segment -----------------------------------------------------------------

    @Test
    fun `read segment is unprobed before any background report exists`() {
        assertEquals(SegmentStatus.UNPROBED, ConduitStateMapper.readSegmentStatus(emptyMap()))
        // A foreground-only report alone says nothing about BACKGROUND read capability.
        assertEquals(
            SegmentStatus.UNPROBED,
            ConduitStateMapper.readSegmentStatus(
                reports(ClipboardReadMode.FOREGROUND_ONLY to CapabilityState.READY),
            ),
        )
    }

    @Test
    fun `read segment is ready when any background mode is ready`() {
        assertEquals(
            SegmentStatus.READY,
            ConduitStateMapper.readSegmentStatus(
                reports(
                    ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.UNAVAILABLE,
                    ClipboardReadMode.OVERLAY_POLLING to CapabilityState.READY,
                ),
            ),
        )
    }

    @Test
    fun `read segment is degraded when best background mode is authorized but unverified`() {
        assertEquals(
            SegmentStatus.DEGRADED,
            ConduitStateMapper.readSegmentStatus(
                reports(
                    ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.DEGRADED,
                    ClipboardReadMode.ADB_LOG_OVERLAY to CapabilityState.UNAVAILABLE,
                ),
            ),
        )
    }

    @Test
    fun `read segment needs action when all background routes are unavailable`() {
        assertEquals(
            SegmentStatus.NEEDS_ACTION,
            ConduitStateMapper.readSegmentStatus(
                reports(
                    ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.UNAVAILABLE,
                    ClipboardReadMode.ADB_LOG_OVERLAY to CapabilityState.UNAVAILABLE,
                    ClipboardReadMode.OVERLAY_POLLING to CapabilityState.UNAVAILABLE,
                ),
            ),
        )
    }

    // ---- write segment ----------------------------------------------------------------

    @Test
    fun `verified public write wins regardless of fallback`() {
        assertEquals(
            SegmentStatus.READY,
            ConduitStateMapper.writeSegmentStatus(CapabilityState.READY, CapabilityState.UNAVAILABLE),
        )
    }

    @Test
    fun `untested public write is unprobed not broken`() {
        assertEquals(
            SegmentStatus.UNPROBED,
            ConduitStateMapper.writeSegmentStatus(CapabilityState.UNKNOWN, CapabilityState.UNAVAILABLE),
        )
    }

    @Test
    fun `failed public write with ready fallback is degraded`() {
        assertEquals(
            SegmentStatus.DEGRADED,
            ConduitStateMapper.writeSegmentStatus(CapabilityState.UNAVAILABLE, CapabilityState.READY),
        )
    }

    @Test
    fun `no write path at all states a fact instead of an error`() {
        assertEquals(
            SegmentStatus.UNAVAILABLE,
            ConduitStateMapper.writeSegmentStatus(CapabilityState.UNAVAILABLE, CapabilityState.UNAVAILABLE),
        )
    }

    // ---- network segment ----------------------------------------------------------------

    @Test
    fun `unpaired network segment needs action`() {
        assertEquals(
            SegmentStatus.NEEDS_ACTION,
            ConduitStateMapper.networkSegmentStatus(paired = false, PeerReachability.UNKNOWN),
        )
    }

    @Test
    fun `paired network segment maps reachability`() {
        assertEquals(
            SegmentStatus.READY,
            ConduitStateMapper.networkSegmentStatus(paired = true, PeerReachability.REACHABLE),
        )
        assertEquals(
            SegmentStatus.DEGRADED,
            ConduitStateMapper.networkSegmentStatus(paired = true, PeerReachability.UNREACHABLE),
        )
        assertEquals(
            SegmentStatus.UNPROBED,
            ConduitStateMapper.networkSegmentStatus(paired = true, PeerReachability.UNKNOWN),
        )
    }

    @Test
    fun `certificate mismatch is surfaced with an error detail`() {
        val inputs = baseInputs().copy(
            paired = true,
            peerName = "Desktop",
            reachability = PeerReachability.CERTIFICATE_MISMATCH,
        )
        val network = ConduitStateMapper.derive(inputs).segments.first { it.id == ConduitSegmentId.NETWORK }
        assertEquals(SegmentStatus.DEGRADED, network.status)
        assertNotNull(network.errorDetail)
    }

    // ---- service segment ----------------------------------------------------------------

    @Test
    fun `service segment maps pairing running and error states`() {
        assertEquals(
            SegmentStatus.UNAVAILABLE,
            ConduitStateMapper.serviceSegmentStatus(paired = false, running = false, errorCode = null),
        )
        assertEquals(
            SegmentStatus.NEEDS_ACTION,
            ConduitStateMapper.serviceSegmentStatus(paired = true, running = false, errorCode = null),
        )
        assertEquals(
            SegmentStatus.READY,
            ConduitStateMapper.serviceSegmentStatus(paired = true, running = true, errorCode = null),
        )
        assertEquals(
            SegmentStatus.DEGRADED,
            ConduitStateMapper.serviceSegmentStatus(paired = true, running = false, errorCode = "FGS_START_REJECTED"),
        )
    }

    @Test
    fun `service actions follow the state`() {
        val running = baseInputs().copy(paired = true, serviceRunning = true)
        val runningSegment =
            ConduitStateMapper.derive(running).segments.first { it.id == ConduitSegmentId.SERVICE }
        assertEquals(listOf(SegmentActionId.STOP_SERVICE), runningSegment.actions.map { it.id })

        val stopped = baseInputs().copy(paired = true, serviceRunning = false)
        val stoppedSegment =
            ConduitStateMapper.derive(stopped).segments.first { it.id == ConduitSegmentId.SERVICE }
        assertEquals(listOf(SegmentActionId.START_SERVICE), stoppedSegment.actions.map { it.id })

        val unpaired = baseInputs().copy(paired = false)
        val unpairedSegment =
            ConduitStateMapper.derive(unpaired).segments.first { it.id == ConduitSegmentId.SERVICE }
        assertTrue(unpairedSegment.actions.isEmpty())
    }

    // ---- single-beckon rule ----------------------------------------------------------------

    @Test
    fun `only the most upstream needy segment beckons`() {
        // All background read routes blocked AND unpaired: read (upstream) must beckon,
        // network keeps its NEEDS_ACTION status but stays quiet (charter §5.6).
        val inputs = baseInputs().copy(
            reports = reports(
                ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.UNAVAILABLE,
                ClipboardReadMode.ADB_LOG_OVERLAY to CapabilityState.UNAVAILABLE,
                ClipboardReadMode.OVERLAY_POLLING to CapabilityState.UNAVAILABLE,
            ),
            paired = false,
        )
        val segments = ConduitStateMapper.derive(inputs).segments
        val read = segments.first { it.id == ConduitSegmentId.READ }
        val network = segments.first { it.id == ConduitSegmentId.NETWORK }
        assertTrue(read.beckoning)
        assertEquals(SegmentStatus.NEEDS_ACTION, network.status)
        assertFalse(network.beckoning)
        assertEquals(1, segments.count { it.beckoning })
    }

    @Test
    fun `network beckons once read is settled`() {
        val inputs = baseInputs().copy(
            reports = reports(ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.DEGRADED),
            paired = false,
        )
        val segments = ConduitStateMapper.derive(inputs).segments
        assertTrue(segments.first { it.id == ConduitSegmentId.NETWORK }.beckoning)
        assertEquals(1, segments.count { it.beckoning })
    }

    // ---- wizard routes ----------------------------------------------------------------

    @Test
    fun `fresh device shows full remaining steps and install action first`() {
        val routes = ConduitStateMapper.routes(baseInputs())
        val shizuku = routes.first { it.id == ReadRouteId.SHIZUKU }
        assertEquals(3, shizuku.stepsRemaining)
        assertEquals(RouteActionId.INSTALL_SHIZUKU, shizuku.nextAction)
        assertEquals(3, shizuku.quality)

        val logOverlay = routes.first { it.id == ReadRouteId.LOG_OVERLAY }
        assertEquals(2, logOverlay.stepsRemaining)
        assertEquals(RouteActionId.COPY_ADB_READ_LOGS_COMMAND, logOverlay.nextAction)

        val polling = routes.first { it.id == ReadRouteId.OVERLAY_POLLING }
        assertEquals(2, polling.stepsRemaining)
        assertEquals(RouteActionId.OPEN_OVERLAY_SETTINGS, polling.nextAction)
        assertEquals(1, polling.quality)
    }

    @Test
    fun `shizuku route walks install launch authorize in order`() {
        val installed = baseInputs().copy(prerequisites = RoutePrerequisites(shizukuInstalled = true))
        assertEquals(
            RouteActionId.LAUNCH_SHIZUKU,
            ConduitStateMapper.routes(installed).first { it.id == ReadRouteId.SHIZUKU }.nextAction,
        )

        val running = baseInputs().copy(
            prerequisites = RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true),
        )
        val route = ConduitStateMapper.routes(running).first { it.id == ReadRouteId.SHIZUKU }
        assertEquals(RouteActionId.REQUEST_SHIZUKU_PERMISSION, route.nextAction)
        assertEquals(1, route.stepsRemaining)
    }

    @Test
    fun `completed route offers preference and preferred route rests`() {
        val complete = RoutePrerequisites(
            shizukuInstalled = true,
            shizukuRunning = true,
            shizukuAuthorized = true,
        )
        val notPreferred = baseInputs().copy(
            prerequisites = complete,
            preferredReadMode = ClipboardReadMode.OVERLAY_POLLING,
        )
        assertEquals(
            RouteActionId.SET_PREFERRED,
            ConduitStateMapper.routes(notPreferred).first { it.id == ReadRouteId.SHIZUKU }.nextAction,
        )

        val preferred = baseInputs().copy(prerequisites = complete)
        val route = ConduitStateMapper.routes(preferred).first { it.id == ReadRouteId.SHIZUKU }
        assertNull(route.nextAction)
        assertTrue(route.preferred)
        assertEquals(0, route.stepsRemaining)
    }

    @Test
    fun `routes carry probe state and error code through to the wizard`() {
        val inputs = baseInputs().copy(
            reports = mapOf(
                ClipboardReadMode.SHIZUKU_EVENT to report(
                    ClipboardReadMode.SHIZUKU_EVENT,
                    CapabilityState.UNAVAILABLE,
                    errorCode = "SHIZUKU_NOT_RUNNING",
                ),
            ),
        )
        val route = ConduitStateMapper.routes(inputs).first { it.id == ReadRouteId.SHIZUKU }
        assertEquals(CapabilityState.UNAVAILABLE, route.readState)
        assertEquals("SHIZUKU_NOT_RUNNING", route.errorCode)
    }

    // ---- read and write stay separate axes ------------------------------------------------

    @Test
    fun `read failure never drags the verified write segment down`() {
        val inputs = baseInputs().copy(
            reports = reports(
                ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.UNAVAILABLE,
                ClipboardReadMode.ADB_LOG_OVERLAY to CapabilityState.UNAVAILABLE,
                ClipboardReadMode.OVERLAY_POLLING to CapabilityState.UNAVAILABLE,
            ),
            publicWriteState = CapabilityState.READY,
        )
        val segments = ConduitStateMapper.derive(inputs).segments
        assertEquals(SegmentStatus.NEEDS_ACTION, segments.first { it.id == ConduitSegmentId.READ }.status)
        assertEquals(SegmentStatus.READY, segments.first { it.id == ConduitSegmentId.WRITE }.status)
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun baseInputs() = ConduitInputs.initial(
        preferredReadMode = ClipboardReadMode.SHIZUKU_EVENT,
        publicWriteState = CapabilityState.UNKNOWN,
    )

    private fun reports(vararg entries: Pair<ClipboardReadMode, CapabilityState>) =
        entries.associate { (mode, state) -> mode to report(mode, state) }

    private fun report(
        mode: ClipboardReadMode,
        state: CapabilityState,
        errorCode: String? = null,
    ) = CapabilityReport(
        readMode = mode,
        readState = state,
        writeState = CapabilityState.UNKNOWN,
        systemVersion = "test",
        errorCode = errorCode,
    )
}
