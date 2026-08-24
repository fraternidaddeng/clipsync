package com.clipsync.android.platform.clipboard

/**
 * Route 3 (charter §4.1): periodic reads through a transient 1x1 overlay focus, no computer
 * needed. This stage implements the honest probe only; the overlay focus controller and the
 * polling loop land with plan stage 5.5. Overlay permission alone is DEGRADED, not READY —
 * the read path must be device-verified first (plan §8.3).
 */
class OverlayPollingBackend(
    private val probes: RouteProbes,
    private val systemVersion: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackgroundClipboardBackend {
    override val mode: ClipboardReadMode = ClipboardReadMode.OVERLAY_POLLING

    override fun probe(): CapabilityReport {
        val p = probes.probe()
        val (state, errorCode) = when {
            !p.overlayGranted -> CapabilityState.UNAVAILABLE to ERROR_OVERLAY_MISSING
            !p.batteryUnrestricted -> CapabilityState.DEGRADED to ERROR_BATTERY_RESTRICTED
            else -> CapabilityState.DEGRADED to ERROR_READ_UNVERIFIED
        }
        return CapabilityReport(
            readMode = mode,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = systemVersion,
            authorizations = listOf(
                ClipboardAuthorization("overlay", p.overlayGranted),
                ClipboardAuthorization("battery_unrestricted", p.batteryUnrestricted),
            ),
            errorCode = errorCode,
        )
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) = Unit

    override fun stop() = Unit

    override fun readText(): ClipboardReadResult = ClipboardReadResult.Failure(ERROR_READ_UNVERIFIED)

    override fun health(): BackendHealth = BackendHealth(
        state = BackendHealthState.STOPPED,
        checkedAtEpochMillis = nowEpochMillis(),
        errorCode = probe().errorCode,
    )

    companion object {
        const val ERROR_OVERLAY_MISSING = "OVERLAY_PERMISSION_MISSING"
        const val ERROR_BATTERY_RESTRICTED = "BATTERY_OPTIMIZATION_RESTRICTED"
        const val ERROR_READ_UNVERIFIED = "OVERLAY_READ_UNVERIFIED"
    }
}
