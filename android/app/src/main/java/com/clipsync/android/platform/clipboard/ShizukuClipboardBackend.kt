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
            !p.shizukuInstalled -> CapabilityState.UNAVAILABLE to ERROR_NOT_INSTALLED
            !p.shizukuRunning -> CapabilityState.UNAVAILABLE to ERROR_NOT_RUNNING
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

    companion object {
        const val ERROR_NOT_INSTALLED = "SHIZUKU_NOT_INSTALLED"
        const val ERROR_NOT_RUNNING = "SHIZUKU_NOT_RUNNING"
        const val ERROR_PERMISSION_DENIED = "SHIZUKU_PERMISSION_DENIED"
        const val ERROR_READ_UNVERIFIED = "SHIZUKU_READ_UNVERIFIED"
    }
}
