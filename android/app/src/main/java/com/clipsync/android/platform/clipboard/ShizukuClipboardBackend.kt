package com.clipsync.android.platform.clipboard

/**
 * Route 1 (charter §4.1): privileged event reads through the Shizuku shell binder. This stage
 * implements the honest probe only — install/running/authorization state with stable error
 * codes. The `IClipboard` event channel itself lands with plan stage 5.3, so an authorized
 * Shizuku reports DEGRADED ("authorized but unverified"), never READY: a mode may only claim
 * READY after real reads succeed on the device (plan §8.3).
 */
class ShizukuClipboardBackend(
    private val probes: RouteProbes,
    private val systemVersion: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackgroundClipboardBackend {
    override val mode: ClipboardReadMode = ClipboardReadMode.SHIZUKU_EVENT

    override fun probe(): CapabilityReport {
        val p = probes.probe()
        val (state, errorCode) = when {
            !p.shizukuInstalled -> CapabilityState.UNAVAILABLE to ERROR_CHANNEL_MISSING
            !p.shizukuRunning -> CapabilityState.UNAVAILABLE to ERROR_CHANNEL_OFFLINE
            !p.shizukuAuthorized -> CapabilityState.UNAVAILABLE to ERROR_PERMISSION_DENIED
            else -> CapabilityState.DEGRADED to ERROR_READ_UNVERIFIED
        }
        return CapabilityReport(
            readMode = mode,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = systemVersion,
            authorizations = listOf(
                ClipboardAuthorization("shizuku_installed", p.shizukuInstalled),
                ClipboardAuthorization("shizuku_running", p.shizukuRunning),
                ClipboardAuthorization("shizuku_authorized", p.shizukuAuthorized),
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

    // Error codes surface in the conduit UI, so they carry the user-facing route
    // name (特权直读 / privileged) rather than the implementation's brand name.
    companion object {
        const val ERROR_CHANNEL_MISSING = "PRIVILEGED_CHANNEL_MISSING"
        const val ERROR_CHANNEL_OFFLINE = "PRIVILEGED_CHANNEL_OFFLINE"
        const val ERROR_PERMISSION_DENIED = "PRIVILEGED_PERMISSION_DENIED"
        const val ERROR_READ_UNVERIFIED = "PRIVILEGED_READ_UNVERIFIED"
    }
}
