package com.clipsync.android.platform.clipboard

/**
 * Route 3 (charter §4.1): periodic reads through a transient 1x1 overlay focus (「悬浮窗轮询」),
 * no computer needed.
 *
 * The honest probe stays here (overlay grant + battery-optimization state). The real polling
 * loop and overlay focus controller are supplied as [delegate] (see
 * [com.clipsync.android.platform.clipboard.overlay.OverlayPollingBackend]); this adapter forwards
 * start/stop/read/health once the route is chosen. Overlay permission alone is DEGRADED, not
 * READY — a device-verified read ([readVerified]) must promote it first (plan §8.3).
 */
class OverlayPollingBackend(
    private val probes: RouteProbes,
    private val systemVersion: String,
    private val delegate: BackgroundClipboardBackend? = null,
    private val readVerified: () -> Boolean = { false },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackgroundClipboardBackend {
    override val mode: ClipboardReadMode = ClipboardReadMode.OVERLAY_POLLING

    override fun probe(): CapabilityReport {
        val p = probes.probe()
        val (state, errorCode) = when {
            !p.overlayGranted -> CapabilityState.UNAVAILABLE to ERROR_OVERLAY_MISSING
            !p.batteryUnrestricted -> CapabilityState.DEGRADED to ERROR_BATTERY_RESTRICTED
            readVerified() -> CapabilityState.READY to null
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

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        delegate?.start(onChanged)
    }

    override fun stop() {
        delegate?.stop()
    }

    override fun readText(): ClipboardReadResult =
        delegate?.readText() ?: ClipboardReadResult.Failure(ERROR_READ_UNVERIFIED)

    override fun health(): BackendHealth =
        delegate?.health() ?: BackendHealth(
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
