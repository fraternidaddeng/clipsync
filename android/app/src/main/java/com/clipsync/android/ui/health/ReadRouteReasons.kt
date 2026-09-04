package com.clipsync.android.ui.health

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.platform.clipboard.AdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.OverlayPollingBackend
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import com.clipsync.android.platform.clipboard.adblog.AdbLogOverlayBackend as AdbLogOverlayRuntime
import com.clipsync.android.platform.clipboard.overlay.OverlayPollingBackend as OverlayPollingRuntime

/**
 * Short phrases for the closed set of read-route error codes that can reach the live route
 * line: the probe codes of every ladder rung (what keeps the preferred route from being READY)
 * and the health codes a running backend fails with (what made the ladder fall back). The line
 * already names the route, so each phrase only states the condition, in the conduit's own
 * vocabulary — never the machine constant. A code outside the set gets null and the caller
 * drops the parenthetical; the code itself stays in the diagnostics log.
 */
object ReadRouteReasons {
    private val phrases: Map<String, Int> =
        mapOf(
            ShizukuErrorCodes.NOT_INSTALLED to R.string.read_route_reason_priv_not_installed,
            ShizukuClipboardBackend.ERROR_CHANNEL_MISSING to R.string.read_route_reason_priv_not_installed,
            ShizukuErrorCodes.NOT_RUNNING to R.string.read_route_reason_priv_not_running,
            ShizukuClipboardBackend.ERROR_CHANNEL_OFFLINE to R.string.read_route_reason_priv_not_running,
            ShizukuErrorCodes.NOT_AUTHORIZED to R.string.read_route_reason_priv_not_authorized,
            ShizukuClipboardBackend.ERROR_PERMISSION_DENIED to R.string.read_route_reason_priv_not_authorized,
            ShizukuErrorCodes.BINDER_DEAD to R.string.read_route_reason_priv_disconnected,
            ShizukuErrorCodes.USERSERVICE_DEAD to R.string.read_route_reason_priv_disconnected,
            ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD to R.string.read_route_reason_clipboard_service_unreachable,
            ShizukuErrorCodes.API_MISMATCH to R.string.read_route_reason_priv_incompatible,
            ShizukuClipboardBackend.ERROR_READ_UNVERIFIED to R.string.read_route_reason_unverified,
            AdbLogOverlayBackend.ERROR_SIGNAL_UNVERIFIED to R.string.read_route_reason_unverified,
            OverlayPollingBackend.ERROR_READ_UNVERIFIED to R.string.read_route_reason_unverified,
            AdbLogOverlayBackend.ERROR_READ_LOGS_NOT_GRANTED to R.string.read_route_reason_read_logs_not_granted,
            AdbLogOverlayRuntime.ERROR_READ_LOGS_NOT_GRANTED to R.string.read_route_reason_read_logs_not_granted,
            AdbLogOverlayRuntime.ERROR_READ_LOGS_REVOKED to R.string.read_route_reason_read_logs_revoked,
            AdbLogOverlayRuntime.ERROR_NO_HEALTHY_SIGNAL to R.string.read_route_reason_no_log_signal,
            // The two probe adapters' ERROR_OVERLAY_MISSING carry this same value.
            OverlayPollingRuntime.ERROR_PERMISSION_MISSING to R.string.read_route_reason_overlay_permission_off,
            OverlayPollingRuntime.ERROR_TOUCHABLE_REQUIRED to R.string.read_route_reason_overlay_unreadable,
            OverlayPollingRuntime.ERROR_SCREEN_NOT_INTERACTIVE to R.string.read_route_reason_screen_off,
            OverlayPollingBackend.ERROR_BATTERY_RESTRICTED to R.string.read_route_reason_battery_restricted,
        )

    fun phraseFor(errorCode: String?): UiText? = errorCode?.let(phrases::get)?.let { UiText.Res(it) }
}
