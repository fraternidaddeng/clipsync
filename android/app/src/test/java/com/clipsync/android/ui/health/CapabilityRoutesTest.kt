package com.clipsync.android.ui.health

import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import com.clipsync.android.ui.ConduitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability state mapping behind the conduit page: five-state fill
 * encoding, single-beckon rule, separate read/write axes and the wizard's
 * remaining steps per route. Pure logic — no Android.
 */
class CapabilityRoutesTest {
    // ---- read segment -------------------------------------------------------------------

    @Test
    fun `read segment is ready when any background mode is ready`() {
        val facts = baseFacts().copy(
            reports = reports(
                ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.UNAVAILABLE,
                ClipboardReadMode.OVERLAY_POLLING to CapabilityState.READY,
            ),
        )
        assertEquals(ConduitStatus.READY, localReadSegmentFromFacts(facts).status)
    }

    @Test
    fun `read segment is degraded when best background mode is authorized but unverified`() {
        val facts = baseFacts().copy(
            reports = reports(
                ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.DEGRADED,
                ClipboardReadMode.ADB_LOG_OVERLAY to CapabilityState.UNAVAILABLE,
            ),
        )
        assertEquals(ConduitStatus.DEGRADED, localReadSegmentFromFacts(facts).status)
    }

    @Test
    fun `read segment needs action when all background routes are unavailable`() {
        assertEquals(
            ConduitStatus.NEEDS_ACTION,
            localReadSegmentFromFacts(allRoutesClosed()).status,
        )
    }

    @Test
    fun `foreground-only report alone says nothing about background read`() {
        val facts = baseFacts().copy(
            reports = reports(ClipboardReadMode.FOREGROUND_ONLY to CapabilityState.READY),
        )
        assertEquals(ConduitStatus.DEGRADED, localReadSegmentFromFacts(facts).status)
        assertEquals("降级 · 仅前台", localReadSegmentFromFacts(facts).statusLabel)
    }

    // ---- write segment (本机写回) ----------------------------------------------------------

    @Test
    fun `untested public write is unprobed not broken`() {
        val segment = localWriteSegmentFromFacts(baseFacts())
        assertEquals(ConduitStatus.UNPROBED, segment.status)
        assertEquals("未测试", segment.statusLabel)
    }

    @Test
    fun `verified public write is ready`() {
        val segment = localWriteSegmentFromFacts(
            baseFacts().copy(publicWriteState = CapabilityState.READY),
        )
        assertEquals(ConduitStatus.READY, segment.status)
    }

    @Test
    fun `failed public write states a fact instead of an error`() {
        val segment = localWriteSegmentFromFacts(
            baseFacts().copy(
                publicWriteState = CapabilityState.UNAVAILABLE,
                publicWriteErrorCode = "CLIPBOARD_WRITE_DENIED",
            ),
        )
        assertEquals(ConduitStatus.UNAVAILABLE, segment.status)
        assertNull(segment.errorDetail)
        assertTrue(segment.detailLines.any { it.contains("CLIPBOARD_WRITE_DENIED") })
    }

    @Test
    fun `read failure never drags the verified write segment down`() {
        val facts = allRoutesClosed().copy(publicWriteState = CapabilityState.READY)
        val state = buildHealthScreenState(peer = null, clipboard = null, sync = null, facts = facts)
        assertEquals(ConduitStatus.NEEDS_ACTION, state.localRead.status)
        assertEquals(ConduitStatus.READY, state.localWrite?.status)
    }

    // ---- network with reachability --------------------------------------------------------

    @Test
    fun `paired network segment maps reachability`() {
        fun networkStatus(reachability: PeerReachability): ConduitStatus =
            buildHealthScreenState(
                peer = peer(),
                clipboard = null,
                sync = null,
                facts = baseFacts().copy(reachability = reachability),
            ).network.status

        assertEquals(ConduitStatus.READY, networkStatus(PeerReachability.REACHABLE))
        assertEquals(ConduitStatus.DEGRADED, networkStatus(PeerReachability.UNREACHABLE))
        assertEquals(ConduitStatus.DEGRADED, networkStatus(PeerReachability.UNKNOWN))
    }

    @Test
    fun `certificate mismatch is surfaced with an error detail`() {
        val state = buildHealthScreenState(
            peer = peer(),
            clipboard = null,
            sync = null,
            facts = baseFacts().copy(reachability = PeerReachability.CERTIFICATE_MISMATCH),
        )
        assertEquals(ConduitStatus.DEGRADED, state.network.status)
        assertNotNull(state.network.errorDetail)
    }

    // ---- single-beckon rule ---------------------------------------------------------------

    @Test
    fun `only the most upstream needy segment beckons`() {
        // All background routes blocked AND unpaired: read (upstream) beckons,
        // network keeps its NEEDS_ACTION status but stays quiet (charter §5.6).
        val state = buildHealthScreenState(
            peer = null,
            clipboard = null,
            sync = null,
            facts = allRoutesClosed(),
        )
        assertTrue(state.localRead.beckoning)
        assertEquals(ConduitStatus.NEEDS_ACTION, state.network.status)
        assertFalse(state.network.beckoning)
        val beckonCount = listOfNotNull(
            state.localRead,
            state.localService,
            state.network,
            state.peerWrite,
            state.localWrite,
        ).count { it.beckoning }
        assertEquals(1, beckonCount)
    }

    @Test
    fun `network beckons once read is settled`() {
        val state = buildHealthScreenState(
            peer = null,
            clipboard = null,
            sync = null,
            facts = baseFacts().copy(
                reports = reports(ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.DEGRADED),
            ),
        )
        assertFalse(state.localRead.beckoning)
        assertTrue(state.network.beckoning)
    }

    // ---- wizard routes ---------------------------------------------------------------------

    @Test
    fun `fresh device shows remaining steps without any install action`() {
        val routes = buildReadRoutes(baseFacts())
        val privileged = routes.first { it.id == ReadRouteId.PRIVILEGED }
        assertEquals(2, privileged.stepsRemaining)
        // Channel availability is a probed fact, never a "go install an app" chore.
        assertNull(privileged.nextAction)
        assertEquals(3, privileged.quality)

        val logOverlay = routes.first { it.id == ReadRouteId.LOG_OVERLAY }
        assertEquals(2, logOverlay.stepsRemaining)
        assertEquals(RouteActionId.COPY_ADB_READ_LOGS_COMMAND, logOverlay.nextAction)

        val polling = routes.first { it.id == ReadRouteId.OVERLAY_POLLING }
        assertEquals(2, polling.stepsRemaining)
        assertEquals(RouteActionId.OPEN_OVERLAY_SETTINGS, polling.nextAction)
        assertEquals(1, polling.quality)
    }

    @Test
    fun `privileged route only offers authorization once its channel is available`() {
        // Installed but not running: the channel step is unsatisfied and has no
        // in-app action — the card states probe facts instead of redirecting.
        val channelDown = baseFacts().copy(
            prerequisites = RoutePrerequisites(shizukuInstalled = true),
        )
        assertNull(buildReadRoutes(channelDown).first { it.id == ReadRouteId.PRIVILEGED }.nextAction)

        val channelUp = baseFacts().copy(
            prerequisites = RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true),
        )
        val route = buildReadRoutes(channelUp).first { it.id == ReadRouteId.PRIVILEGED }
        assertEquals(RouteActionId.REQUEST_PRIVILEGED_PERMISSION, route.nextAction)
        assertEquals(1, route.stepsRemaining)
    }

    @Test
    fun `no user-facing wizard string mentions the backing implementation brand`() {
        val routes = buildReadRoutes(baseFacts())
        val visible = routes.flatMap { route ->
            listOf(route.title, route.cost) + route.steps.map { it.label }
        } + RouteActionId.entries.map(::routeActionLabel) +
            ClipboardReadMode.entries.map(::readModeTitle)
        visible.forEach { text ->
            assertFalse("\"$text\" leaks the brand name", text.contains("shizuku", ignoreCase = true))
        }
    }

    @Test
    fun `completed route offers preference and preferred route rests`() {
        val complete = RoutePrerequisites(
            shizukuInstalled = true,
            shizukuRunning = true,
            shizukuAuthorized = true,
        )
        val notPreferred = baseFacts().copy(
            prerequisites = complete,
            preferredReadMode = ClipboardReadMode.OVERLAY_POLLING,
        )
        assertEquals(
            RouteActionId.SET_PREFERRED,
            buildReadRoutes(notPreferred).first { it.id == ReadRouteId.PRIVILEGED }.nextAction,
        )

        val preferred = baseFacts().copy(prerequisites = complete)
        val route = buildReadRoutes(preferred).first { it.id == ReadRouteId.PRIVILEGED }
        assertNull(route.nextAction)
        assertTrue(route.preferred)
        assertEquals(0, route.stepsRemaining)
    }

    @Test
    fun `routes carry probe state and error code through to the wizard`() {
        val facts = baseFacts().copy(
            reports = mapOf(
                ClipboardReadMode.SHIZUKU_EVENT to report(
                    ClipboardReadMode.SHIZUKU_EVENT,
                    CapabilityState.UNAVAILABLE,
                    errorCode = "PRIVILEGED_CHANNEL_OFFLINE",
                ),
            ),
        )
        val route = buildReadRoutes(facts).first { it.id == ReadRouteId.PRIVILEGED }
        assertEquals(CapabilityState.UNAVAILABLE, route.readState)
        assertEquals("PRIVILEGED_CHANNEL_OFFLINE", route.errorCode)
    }

    @Test
    fun `overlay polling route only needs overlay and battery`() {
        val facts = baseFacts().copy(
            prerequisites = RoutePrerequisites(overlayGranted = true, batteryUnrestricted = true),
            preferredReadMode = ClipboardReadMode.OVERLAY_POLLING,
        )
        val route = buildReadRoutes(facts).first { it.id == ReadRouteId.OVERLAY_POLLING }
        assertEquals(0, route.stepsRemaining)
        assertNull(route.nextAction)
        assertTrue(route.preferred)
    }

    // ---- service segment with error code ----------------------------------------------------

    @Test
    fun `service start failure surfaces its stable error code`() {
        val state = buildHealthScreenState(
            peer = peer(),
            clipboard = null,
            sync = SyncHealth(
                serviceRunning = false,
                connected = false,
                serviceErrorCode = "FGS_START_REJECTED",
            ),
        )
        assertEquals(ConduitStatus.DEGRADED, state.localService.status)
        assertTrue(state.localService.detail.contains("FGS_START_REJECTED"))
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun baseFacts() = CapabilityFacts(
        reports = emptyMap(),
        prerequisites = RoutePrerequisites(),
        preferredReadMode = ClipboardReadMode.SHIZUKU_EVENT,
        publicWriteState = CapabilityState.UNKNOWN,
    )

    private fun allRoutesClosed() = baseFacts().copy(
        reports = reports(
            ClipboardReadMode.SHIZUKU_EVENT to CapabilityState.UNAVAILABLE,
            ClipboardReadMode.ADB_LOG_OVERLAY to CapabilityState.UNAVAILABLE,
            ClipboardReadMode.OVERLAY_POLLING to CapabilityState.UNAVAILABLE,
        ),
    )

    private fun peer() = PairedPeer(
        deviceId = "11111111-1111-4111-8111-111111111111",
        displayName = "DESKTOP-WIN",
        platform = "windows",
        certSha256 = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25",
        trustEpoch = 1,
        hosts = listOf("192.168.1.23"),
        port = 47654,
        pairedAtMs = 1_755_000_000_000,
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
