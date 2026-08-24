package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The probe-only backends must map real prerequisite facts to honest capability states:
 * never READY before a device-verified read, always a stable error code for the missing piece.
 */
class CapabilityProbeBackendsTest {
    private class FixedProbes(private val prerequisites: RoutePrerequisites) : RouteProbes {
        override fun probe(): RoutePrerequisites = prerequisites
    }

    private fun shizuku(p: RoutePrerequisites) =
        ShizukuClipboardBackend(FixedProbes(p), systemVersion = "test").probe()

    private fun adbLog(p: RoutePrerequisites) =
        AdbLogOverlayBackend(FixedProbes(p), systemVersion = "test").probe()

    private fun polling(p: RoutePrerequisites) =
        OverlayPollingBackend(FixedProbes(p), systemVersion = "test").probe()

    @Test
    fun `shizuku reports each missing prerequisite with its own code`() {
        val notInstalled = shizuku(RoutePrerequisites())
        assertEquals(CapabilityState.UNAVAILABLE, notInstalled.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_NOT_INSTALLED, notInstalled.errorCode)

        val notRunning = shizuku(RoutePrerequisites(shizukuInstalled = true))
        assertEquals(CapabilityState.UNAVAILABLE, notRunning.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_NOT_RUNNING, notRunning.errorCode)

        val denied = shizuku(RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true))
        assertEquals(CapabilityState.UNAVAILABLE, denied.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_PERMISSION_DENIED, denied.errorCode)
    }

    @Test
    fun `authorized shizuku is degraded not ready until reads are device-verified`() {
        val authorized = shizuku(
            RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true, shizukuAuthorized = true),
        )
        assertEquals(CapabilityState.DEGRADED, authorized.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_READ_UNVERIFIED, authorized.errorCode)
    }

    @Test
    fun `shizuku probe reports the authorization trail`() {
        val report = shizuku(RoutePrerequisites(shizukuInstalled = true))
        assertEquals(
            listOf("shizuku_installed" to true, "shizuku_running" to false, "shizuku_authorized" to false),
            report.authorizations.map { it.name to it.granted },
        )
    }

    @Test
    fun `granted READ_LOGS alone is never treated as a working signal`() {
        val nothing = adbLog(RoutePrerequisites())
        assertEquals(CapabilityState.UNAVAILABLE, nothing.readState)
        assertEquals(AdbLogOverlayBackend.ERROR_READ_LOGS_NOT_GRANTED, nothing.errorCode)

        val noOverlay = adbLog(RoutePrerequisites(readLogsGranted = true))
        assertEquals(CapabilityState.UNAVAILABLE, noOverlay.readState)
        assertEquals(AdbLogOverlayBackend.ERROR_OVERLAY_MISSING, noOverlay.errorCode)

        val granted = adbLog(RoutePrerequisites(readLogsGranted = true, overlayGranted = true))
        assertEquals(CapabilityState.DEGRADED, granted.readState)
        assertEquals(AdbLogOverlayBackend.ERROR_SIGNAL_UNVERIFIED, granted.errorCode)
    }

    @Test
    fun `overlay polling needs the overlay grant and flags battery restriction`() {
        val missing = polling(RoutePrerequisites())
        assertEquals(CapabilityState.UNAVAILABLE, missing.readState)
        assertEquals(OverlayPollingBackend.ERROR_OVERLAY_MISSING, missing.errorCode)

        val restricted = polling(RoutePrerequisites(overlayGranted = true))
        assertEquals(CapabilityState.DEGRADED, restricted.readState)
        assertEquals(OverlayPollingBackend.ERROR_BATTERY_RESTRICTED, restricted.errorCode)

        val granted = polling(RoutePrerequisites(overlayGranted = true, batteryUnrestricted = true))
        assertEquals(CapabilityState.DEGRADED, granted.readState)
        assertEquals(OverlayPollingBackend.ERROR_READ_UNVERIFIED, granted.errorCode)
    }
}
