package com.clipsync.android.platform.clipboard

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.clipsync.android.platform.clipboard.adblog.AdbLogOverlayBackend as RealAdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.overlay.OverlayFocusController
import com.clipsync.android.platform.clipboard.overlay.OverlayPollingBackend as RealOverlayPollingBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuClipboardBackend as RealShizukuClipboardBackend

/**
 * Builds the real device background-read backends (plan stage 5.3–5.5) and hands them to the
 * flat capability-ladder adapters in [com.clipsync.android.platform.clipboard]. The two
 * overlay-based routes (「日志感知 + 悬浮窗」and「悬浮窗轮询」) share one [OverlayFocusController]
 * so only one transient 1x1 focus window ever exists.
 *
 * Construction alone starts nothing, probes nothing, and flips no capability to READY — the
 * flat adapters gate start/READY on the honest prerequisite probe plus a device-verified read.
 *
 * Physical device still required to validate: PrivilegedHostService binder attach,
 * PrivilegedUserServiceStarter → ClipboardUserService, logcat parsers per ROM, and overlay
 * focus on real WindowManager implementations. JVM fakes cover coordinator wiring only.
 */
class RealBackgroundReaders private constructor(
    val shizuku: BackgroundClipboardBackend,
    val adbLog: BackgroundClipboardBackend,
    val overlayPolling: BackgroundClipboardBackend,
) {
    /** Privileged write fallback backed by the same Shizuku UserService as [shizuku], when available. */
    fun shizukuWriter(): ClipboardWriter? =
        (shizuku as? RealShizukuClipboardBackend)?.fallbackWriter()

    /** Ask Shizuku to authorize this app; the result arrives on the injected callback. */
    fun requestShizukuAuthorization(onResult: (granted: Boolean) -> Unit) {
        (shizuku as? RealShizukuClipboardBackend)?.requestAuthorization(onResult) ?: onResult(false)
    }

    companion object {
        fun build(context: Context): RealBackgroundReaders {
            val app = context.applicationContext
            val overlayController = OverlayFocusController(app)
            val shizuku = RealShizukuClipboardBackend(app)
            val adbLog = RealAdbLogOverlayBackend(
                app,
                overlayController::readText,
                overlayController::detach,
            )
            val overlayPolling = RealOverlayPollingBackend(
                controller = overlayController,
                canPollNow = { canPollNow(app) },
            )
            return RealBackgroundReaders(
                shizuku = shizuku,
                adbLog = adbLog,
                overlayPolling = overlayPolling,
            )
        }

        // Overlay reads only make sense while the screen is on and unlocked; polling pauses
        // otherwise so the transient focus window is never raised over a locked device.
        private fun canPollNow(context: Context): Boolean {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val interactive = power?.isInteractive ?: true
            val locked = keyguard?.isKeyguardLocked ?: false
            return interactive && !locked
        }
    }
}
