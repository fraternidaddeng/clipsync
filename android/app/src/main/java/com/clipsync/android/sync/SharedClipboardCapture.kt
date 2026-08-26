package com.clipsync.android.sync

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.clipsync.android.R
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.AdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.AndroidRouteProbes
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardCaptureSession
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ForegroundClipboardBackend
import com.clipsync.android.platform.clipboard.OverlayPollingBackend
import com.clipsync.android.platform.clipboard.RealBackgroundReaders
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.platform.clipboard.SharedClipboardWrites
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.storage.SyncSettingsStore

/**
 * The full local capture stack, built once per process: the capability ladder backends, the
 * [ClipboardAccessCoordinator] that arbitrates them, the [ClipboardCaptureManager] policy
 * engine every captured change passes through (plan 5.6), and the [ClipboardCaptureSession]
 * that decides when the coordinator runs at all.
 *
 * One instance matters: if the activity and the foreground service each built their own
 * coordinator, two backends could listen at once and double-announce every copy. The conduit
 * page (probes, read tests, mode choice) and both lifecycle owners must see the same objects.
 */
class CaptureStack(
    val capabilityStore: ClipboardCapabilityStore,
    val routeProbes: RouteProbes,
    val realReaders: RealBackgroundReaders,
    val foregroundBackend: ForegroundClipboardBackend,
    val coordinator: ClipboardAccessCoordinator,
    val session: ClipboardCaptureSession,
)

/**
 * Process-wide holder for the [CaptureStack] (same pattern as
 * [SharedClipboardWrites]): [MainActivity][com.clipsync.android.MainActivity] and
 * [ClipboardSyncService] resolve their shared capture wiring here, so the service can own the
 * coordinator lifecycle while it is promoted (plan 5.2) and the activity's visibility only
 * matters when no service runs.
 */
object SharedClipboardCapture {
    @Volatile
    private var sharedStack: CaptureStack? = null

    /** Replaceable seam so tests can swap the stack for one built on fake backends. */
    var stackProvider: (Context) -> CaptureStack = { context ->
        sharedStack ?: synchronized(this) {
            sharedStack ?: buildStack(context.applicationContext).also { sharedStack = it }
        }
    }

    fun stack(context: Context): CaptureStack = stackProvider(context)

    fun session(context: Context): ClipboardCaptureSession = stack(context).session

    private fun buildStack(appContext: Context): CaptureStack {
        val settings =
            SyncSettingsStore(
                SharedPrefsKeyValueStore(appContext, name = SyncSettingsStore.PREFERENCES_NAME),
            )
        val capabilityStore =
            ClipboardCapabilityStore(
                SharedPrefsKeyValueStore(appContext, name = CAPABILITY_PREFERENCES_NAME),
            )
        val routeProbes = AndroidRouteProbes(appContext)
        val systemVersion = "android-${Build.VERSION.SDK_INT}"
        // The real device read backends (特权直读 privileged channel, logcat+overlay, overlay
        // polling). The flat capability-ladder adapters wrap these and gate them on the
        // honest probe plus the persisted device-verified read.
        val realReaders = RealBackgroundReaders.build(appContext)
        val foregroundBackend =
            ForegroundClipboardBackend(
                appContext,
                systemVersion = systemVersion,
                // Re-read per clipboard change so the preference toggle applies immediately.
                imageCaptureEnabled = { settings.imageSyncEnabled },
            )
        val coordinator =
            buildCoordinator(
                capabilityStore = capabilityStore,
                routeProbes = routeProbes,
                systemVersion = systemVersion,
                realReaders = realReaders,
                foregroundBackend = foregroundBackend,
            )
        val captureManager =
            ClipboardCaptureManager(
                settings = settings,
                // One process-wide write coordinator: its suppression table is what keeps
                // auto-applied remote clips from echoing straight back to the peer.
                writeCoordinator = SharedClipboardWrites.coordinator(appContext),
                imageSink = { bytes ->
                    ImageClipSink.submit(appContext, bytes, "android.app") is
                        ImageClipSink.Outcome.Accepted
                },
            )
        val mainHandler = Handler(Looper.getMainLooper())
        return CaptureStack(
            capabilityStore = capabilityStore,
            routeProbes = routeProbes,
            realReaders = realReaders,
            foregroundBackend = foregroundBackend,
            coordinator = coordinator,
            session =
                ClipboardCaptureSession(
                    coordinator = coordinator,
                    onChanged = { change ->
                        val outcome = captureManager.onClipboardChanged(change)
                        if (outcome == CaptureOutcome.REJECTED_TOO_LARGE) {
                            announceOversizeRejection(appContext, mainHandler)
                        }
                    },
                    // Backend-level gate: while sync or auto-capture is paused, or private mode
                    // is on, nothing may even read the clipboard in the background (the
                    // per-event gates above remain the authority for anything that arrives).
                    captureAllowed = {
                        !settings.syncPaused && !settings.privateMode && !settings.autoCapturePaused
                    },
                ),
        )
    }

    /**
     * 超限内容本机保留 + 明确提示，不得静默 (plan 3.3 rule 9): the copy stays on the
     * clipboard untruncated, and the user hears why it will not appear on the
     * other device. Size fact only, no content.
     */
    private fun announceOversizeRejection(
        appContext: Context,
        mainHandler: Handler,
    ) {
        mainHandler.post {
            Toast
                .makeText(appContext, R.string.toast_capture_too_large, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun buildCoordinator(
        capabilityStore: ClipboardCapabilityStore,
        routeProbes: RouteProbes,
        systemVersion: String,
        realReaders: RealBackgroundReaders,
        foregroundBackend: ForegroundClipboardBackend,
    ): ClipboardAccessCoordinator =
        ClipboardAccessCoordinator(
            backends =
                listOf(
                    ShizukuClipboardBackend(
                        probes = routeProbes,
                        systemVersion = systemVersion,
                        delegate = realReaders.shizuku,
                        readVerified = { capabilityStore.isReadVerified(ClipboardReadMode.SHIZUKU_EVENT) },
                    ),
                    AdbLogOverlayBackend(
                        probes = routeProbes,
                        systemVersion = systemVersion,
                        delegate = realReaders.adbLog,
                        readVerified = { capabilityStore.isReadVerified(ClipboardReadMode.ADB_LOG_OVERLAY) },
                    ),
                    OverlayPollingBackend(
                        probes = routeProbes,
                        systemVersion = systemVersion,
                        delegate = realReaders.overlayPolling,
                        readVerified = { capabilityStore.isReadVerified(ClipboardReadMode.OVERLAY_POLLING) },
                    ),
                    foregroundBackend,
                ),
            requestedReadMode = capabilityStore.preferredReadMode(),
            autoFallbackAllowed = capabilityStore.autoFallbackAllowed(),
        )

    /** Test hook: Robolectric recreates the application per test, so drop the cached stack. */
    fun reset() {
        sharedStack = null
    }

    private const val CAPABILITY_PREFERENCES_NAME = "clipsync.capability"
}
