package com.clipsync.android.platform.clipboard

import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The probe-only backends must map real prerequisite facts to honest capability states:
 * never READY before a device-verified read, always a stable error code for the missing piece —
 * and never READY over a live delegate that says the channel is currently dead.
 */
class CapabilityProbeBackendsTest {
    private class FixedProbes(
        private val prerequisites: RoutePrerequisites,
    ) : RouteProbes {
        override fun probe(): RoutePrerequisites = prerequisites
    }

    private fun shizuku(p: RoutePrerequisites) = ShizukuClipboardBackend(FixedProbes(p), systemVersion = "test").probe()

    private val authorized =
        RoutePrerequisites(
            shizukuInstalled = true,
            shizukuRunning = true,
            shizukuAuthorized = true,
        )

    private fun adbLog(p: RoutePrerequisites) =
        AdbLogOverlayBackend(FixedProbes(p), systemVersion = "test").probe()

    private fun polling(p: RoutePrerequisites) = OverlayPollingBackend(FixedProbes(p), systemVersion = "test").probe()

    @Test
    fun `shizuku reports each missing prerequisite with its own code`() {
        val notInstalled = shizuku(RoutePrerequisites())
        assertEquals(CapabilityState.UNAVAILABLE, notInstalled.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_CHANNEL_MISSING, notInstalled.errorCode)

        val notRunning = shizuku(RoutePrerequisites(shizukuInstalled = true))
        assertEquals(CapabilityState.UNAVAILABLE, notRunning.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_CHANNEL_OFFLINE, notRunning.errorCode)

        val denied = shizuku(RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true))
        assertEquals(CapabilityState.UNAVAILABLE, denied.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_PERMISSION_DENIED, denied.errorCode)
    }

    @Test
    fun `authorized shizuku is degraded not ready until reads are device-verified`() {
        val authorized =
            shizuku(
                RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true, shizukuAuthorized = true),
            )
        assertEquals(CapabilityState.DEGRADED, authorized.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_READ_UNVERIFIED, authorized.errorCode)
    }

    // ===== 特权直读: a persisted "verified" must not outshout the live channel =====

    private fun allPrerequisitesMet() =
        FixedProbes(
            RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true, shizukuAuthorized = true),
        )

    private fun verifiedShizuku(delegate: FakeBackgroundClipboardBackend?) =
        ShizukuClipboardBackend(
            probes = allPrerequisitesMet(),
            systemVersion = "test",
            delegate = delegate,
            readVerified = { true },
        )

    private fun delegateWithHealth(
        state: BackendHealthState,
        errorCode: String? = null,
    ) = FakeBackgroundClipboardBackend(
        mode = ClipboardReadMode.SHIZUKU_EVENT,
        backendHealth = BackendHealth(state, checkedAtEpochMillis = 1L, errorCode = errorCode),
    )

    @Test
    fun `verified shizuku is ready while the live delegate is healthy or not active`() {
        val healthy = verifiedShizuku(delegateWithHealth(BackendHealthState.HEALTHY)).probe()
        assertEquals(CapabilityState.READY, healthy.readState)
        assertNull(healthy.errorCode)

        // Route verified earlier but not currently active (e.g. another route running):
        // the stopped delegate carries no live evidence against the persisted verification.
        val stopped = verifiedShizuku(delegateWithHealth(BackendHealthState.STOPPED)).probe()
        assertEquals(CapabilityState.READY, stopped.readState)
        assertNull(stopped.errorCode)

        // No delegate wired on this build: same, nothing live to contradict.
        val noDelegate = verifiedShizuku(delegate = null).probe()
        assertEquals(CapabilityState.READY, noDelegate.readState)
        assertNull(noDelegate.errorCode)
    }

    @Test
    fun `live userservice death demotes a verified shizuku route to its stable code`() {
        // The wireless-debugging gap in one probe: the host's shell binder still answers
        // (prerequisites all pass) and a read was verified days ago, but the UserService
        // died — the phone rebooted or the system reclaimed it. Claiming READY here is the
        // lie that left users staring at a "working" route that delivers nothing.
        val report =
            verifiedShizuku(
                delegateWithHealth(BackendHealthState.FAILED, ShizukuErrorCodes.USERSERVICE_DEAD),
            ).probe()

        assertEquals(CapabilityState.UNAVAILABLE, report.readState)
        assertEquals(ShizukuErrorCodes.USERSERVICE_DEAD, report.errorCode)
    }

    @Test
    fun `live binder and clipboard deaths demote a verified shizuku route too`() {
        val binderDead =
            verifiedShizuku(
                delegateWithHealth(BackendHealthState.FAILED, ShizukuErrorCodes.BINDER_DEAD),
            ).probe()
        assertEquals(CapabilityState.UNAVAILABLE, binderDead.readState)
        assertEquals(ShizukuErrorCodes.BINDER_DEAD, binderDead.errorCode)

        val clipboardDead =
            verifiedShizuku(
                delegateWithHealth(BackendHealthState.FAILED, ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD),
            ).probe()
        assertEquals(CapabilityState.UNAVAILABLE, clipboardDead.readState)
        assertEquals(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD, clipboardDead.errorCode)
    }

    @Test
    fun `a transient degraded delegate without a code leaves the verified READY standing`() {
        // Mid-rebind the delegate reports DEGRADED with no error code (it is trying); the
        // probe keeps the verified READY instead of flapping the card every backoff beat.
        val report = verifiedShizuku(delegateWithHealth(BackendHealthState.DEGRADED)).probe()

        assertEquals(CapabilityState.READY, report.readState)
        assertNull(report.errorCode)
    }

    @Test
    fun `authorized shizuku surfaces a proven-dead read channel instead of pending-test`() {
        // Host pings and the app is authorized, but the last device read test proved the
        // UserService dead (e.g. the wireless-debugging shell that launched it dropped): the
        // probe must keep saying so, never revert to the rosy "授权但待实测".
        val report =
            ShizukuClipboardBackend(
                probes = FixedProbes(authorized),
                systemVersion = "test",
                lastReadFailureCode = { ShizukuErrorCodes.USERSERVICE_DEAD },
            ).probe()
        assertEquals(CapabilityState.UNAVAILABLE, report.readState)
        assertEquals(ShizukuErrorCodes.USERSERVICE_DEAD, report.errorCode)
    }

    @Test
    fun `authorized shizuku still just awaits its first test when nothing failed`() {
        val report =
            ShizukuClipboardBackend(
                probes = FixedProbes(authorized),
                systemVersion = "test",
                lastReadFailureCode = { null },
            ).probe()
        assertEquals(CapabilityState.DEGRADED, report.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_READ_UNVERIFIED, report.errorCode)
    }

    @Test
    fun `a verified read wins over a stale failure record`() {
        val report =
            ShizukuClipboardBackend(
                probes = FixedProbes(authorized),
                systemVersion = "test",
                readVerified = { true },
                lastReadFailureCode = { ShizukuErrorCodes.USERSERVICE_DEAD },
            ).probe()
        assertEquals(CapabilityState.READY, report.readState)
        assertNull(report.errorCode)
    }

    @Test
    fun `shizuku probe reports the authorization trail`() {
        val report = shizuku(RoutePrerequisites(shizukuInstalled = true))
        assertEquals(
            listOf("priv_host_installed" to true, "priv_host_running" to false, "priv_host_authorized" to false),
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
