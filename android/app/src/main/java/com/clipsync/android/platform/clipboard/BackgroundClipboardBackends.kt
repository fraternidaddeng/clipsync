package com.clipsync.android.platform.clipboard

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.os.PowerManager
import com.clipsync.android.platform.clipboard.adblog.AdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.overlay.OverlayFocusController
import com.clipsync.android.platform.clipboard.overlay.OverlayPollingBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuClipboardBackend

/**
 * Assembles the four background clipboard backends in [FALLBACK_ORDER] plus the
 * overlay focus controller. Construction does not start listeners, does not
 * probe, and does not flip any capability to READY.
 *
 * Default [requestedReadMode] is [ClipboardReadMode.FOREGROUND_ONLY] with
 * auto-fallback off so adopting [coordinator] later cannot silently enable
 * overlay / ADB / Shizuku.
 */
class BackgroundClipboardBackends(
    val overlayController: OverlayFocusController,
    val backends: List<BackgroundClipboardBackend>,
    val requestedReadMode: ClipboardReadMode = ClipboardReadMode.FOREGROUND_ONLY,
    val autoFallbackAllowed: Boolean = false,
    val overlayConsented: Boolean = true,
    val capabilityStore: ClipboardCapabilityStore? = null,
) {
    val shizuku: BackgroundClipboardBackend? = backend(ClipboardReadMode.SHIZUKU_EVENT)
    val adbLog: BackgroundClipboardBackend? = backend(ClipboardReadMode.ADB_LOG_OVERLAY)
    val overlayPolling: BackgroundClipboardBackend? = backend(ClipboardReadMode.OVERLAY_POLLING)
    val foreground: BackgroundClipboardBackend? = backend(ClipboardReadMode.FOREGROUND_ONLY)

    init {
        require(backends.map { it.mode }.toSet().size == backends.size) {
            "Clipboard backend modes must be unique."
        }
    }

    fun backend(mode: ClipboardReadMode): BackgroundClipboardBackend? =
        backends.firstOrNull { it.mode == mode }

    /**
     * First assembled backend from [requestedReadMode] downward through
     * [FALLBACK_ORDER]. Missing ClipboardManager (no foreground backend) skips
     * that slot; it does not walk to a READY peer.
     */
    fun selectedEligibleBackend(
        requestedReadMode: ClipboardReadMode = this.requestedReadMode,
    ): BackgroundClipboardBackend? {
        val start = FALLBACK_ORDER.indexOf(requestedReadMode)
        val modes = if (start < 0) FALLBACK_ORDER else FALLBACK_ORDER.drop(start)
        return modes.firstNotNullOfOrNull { mode ->
            if (!overlayConsented && isOverlayReadMode(mode)) {
                null
            } else {
                backend(mode)
            }
        }
    }

    fun selectedReadState(
        requestedReadMode: ClipboardReadMode = this.requestedReadMode,
    ): CapabilityState =
        selectedEligibleBackend(requestedReadMode)?.probe()?.readState
            ?: CapabilityState.UNAVAILABLE

    fun coordinator(
        requestedReadMode: ClipboardReadMode = this.requestedReadMode,
        autoFallbackAllowed: Boolean = this.autoFallbackAllowed,
        capabilityStore: ClipboardCapabilityStore? = this.capabilityStore,
        overlayConsented: Boolean = this.overlayConsented,
    ): ClipboardAccessCoordinator = ClipboardAccessCoordinator(
        backends = selectableBackends(overlayConsented),
        requestedReadMode = requestedReadMode,
        autoFallbackAllowed = autoFallbackAllowed,
        capabilityStore = capabilityStore,
        releaseFocusResource = overlayController::detach,
    )

    private fun selectableBackends(overlayConsented: Boolean): List<BackgroundClipboardBackend> =
        if (overlayConsented) {
            backends
        } else {
            backends.filter { backend -> !isOverlayReadMode(backend.mode) }
        }

    companion object {
        val FALLBACK_ORDER = listOf(
            ClipboardReadMode.SHIZUKU_EVENT,
            ClipboardReadMode.ADB_LOG_OVERLAY,
            ClipboardReadMode.OVERLAY_POLLING,
            ClipboardReadMode.FOREGROUND_ONLY,
        )

        fun build(
            context: Context,
            isVisible: () -> Boolean,
            capabilityStore: ClipboardCapabilityStore? = null,
            requestedReadMode: ClipboardReadMode = ClipboardReadMode.FOREGROUND_ONLY,
            autoFallbackAllowed: Boolean = false,
            overlayConsented: Boolean = true,
            pollIntervalMillis: Long = OverlayPollingBackend.DEFAULT_POLL_INTERVAL_MS,
            canPollNow: () -> Boolean = defaultCanPollNow(context),
            overlayController: OverlayFocusController = OverlayFocusController(
                context.applicationContext,
            ),
            shizuku: BackgroundClipboardBackend = ShizukuClipboardBackend(
                context.applicationContext,
            ),
            adbLog: BackgroundClipboardBackend = AdbLogOverlayBackend(
                context.applicationContext,
                overlayController::readText,
                overlayController::detach,
            ),
            overlayPolling: BackgroundClipboardBackend = OverlayPollingBackend(
                controller = overlayController,
                canPollNow = canPollNow,
                pollIntervalMillis = pollIntervalMillis,
            ),
            foreground: BackgroundClipboardBackend? = clipboardManagerOrNull(context)?.let { manager ->
                ForegroundClipboardBackend(context.applicationContext, manager, isVisible)
            },
        ): BackgroundClipboardBackends = build(
            overlayController = overlayController,
            shizuku = shizuku,
            adbLog = adbLog,
            overlayPolling = overlayPolling,
            foreground = foreground,
            capabilityStore = capabilityStore,
            requestedReadMode = requestedReadMode,
            autoFallbackAllowed = autoFallbackAllowed,
            overlayConsented = overlayConsented,
        )

        fun build(
            overlayController: OverlayFocusController,
            shizuku: BackgroundClipboardBackend,
            adbLog: BackgroundClipboardBackend,
            overlayPolling: BackgroundClipboardBackend,
            foreground: BackgroundClipboardBackend?,
            capabilityStore: ClipboardCapabilityStore? = null,
            requestedReadMode: ClipboardReadMode = ClipboardReadMode.FOREGROUND_ONLY,
            autoFallbackAllowed: Boolean = false,
            overlayConsented: Boolean = true,
        ): BackgroundClipboardBackends {
            val byMode = listOfNotNull(shizuku, adbLog, overlayPolling, foreground)
                .associateBy { it.mode }
            val ordered = FALLBACK_ORDER.mapNotNull { byMode[it] }
            return BackgroundClipboardBackends(
                overlayController = overlayController,
                backends = ordered,
                requestedReadMode = requestedReadMode,
                autoFallbackAllowed = autoFallbackAllowed,
                overlayConsented = overlayConsented,
                capabilityStore = capabilityStore,
            )
        }

        fun isOverlayReadMode(mode: ClipboardReadMode): Boolean =
            mode == ClipboardReadMode.ADB_LOG_OVERLAY ||
                mode == ClipboardReadMode.OVERLAY_POLLING

        fun defaultCanPollNow(context: Context): () -> Boolean {
            val app = context.applicationContext
            return {
                val power = app.getSystemService(PowerManager::class.java)
                val keyguard = app.getSystemService(KeyguardManager::class.java)
                (power == null || power.isInteractive) &&
                    (keyguard == null || !keyguard.isKeyguardLocked)
            }
        }

        private fun clipboardManagerOrNull(context: Context): ClipboardManager? =
            context.applicationContext.getSystemService(ClipboardManager::class.java)
    }
}
