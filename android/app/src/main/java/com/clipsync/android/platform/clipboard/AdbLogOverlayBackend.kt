package com.clipsync.android.platform.clipboard

/**
 * Route 2 (charter §4.1): copy signals from the restricted logcat stream, content through a
 * transient overlay focus (「日志感知 + 悬浮窗」).
 *
 * The honest probe stays here (READ_LOGS + overlay grant). The real logcat reader and overlay
 * focus controller are supplied as [delegate] (see
 * [com.clipsync.android.platform.clipboard.adblog.AdbLogOverlayBackend]); this adapter forwards
 * start/stop/read/health once the route is chosen. A granted READ_LOGS is deliberately NOT
 * treated as READY — the plan (§0.1.2 rule 2) requires a device-verified read
 * ([readVerified]) before this route may claim more than DEGRADED.
 */
class AdbLogOverlayBackend(
    private val probes: RouteProbes,
    private val systemVersion: String,
    private val delegate: BackgroundClipboardBackend? = null,
    private val readVerified: () -> Boolean = { false },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackgroundClipboardBackend {
    override val mode: ClipboardReadMode = ClipboardReadMode.ADB_LOG_OVERLAY

    override fun probe(): CapabilityReport {
        val p = probes.probe()
        val (state, errorCode) = when {
            !p.readLogsGranted -> CapabilityState.UNAVAILABLE to ERROR_READ_LOGS_NOT_GRANTED
            !p.overlayGranted -> CapabilityState.UNAVAILABLE to ERROR_OVERLAY_MISSING
            readVerified() -> CapabilityState.READY to null
            else -> CapabilityState.DEGRADED to ERROR_SIGNAL_UNVERIFIED
        }
        return CapabilityReport(
            readMode = mode,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = systemVersion,
            authorizations = listOf(
                ClipboardAuthorization("read_logs", p.readLogsGranted),
                ClipboardAuthorization("overlay", p.overlayGranted),
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
        delegate?.readText() ?: ClipboardReadResult.Failure(ERROR_SIGNAL_UNVERIFIED)

    override fun health(): BackendHealth =
        delegate?.health() ?: BackendHealth(
            state = BackendHealthState.STOPPED,
            checkedAtEpochMillis = nowEpochMillis(),
            errorCode = probe().errorCode,
        )

    companion object {
        const val ERROR_READ_LOGS_NOT_GRANTED = "READ_LOGS_NOT_GRANTED"
        const val ERROR_OVERLAY_MISSING = "OVERLAY_PERMISSION_MISSING"
        const val ERROR_SIGNAL_UNVERIFIED = "ADB_SIGNAL_UNVERIFIED"
    }
}
