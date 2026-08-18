package com.clipsync.android.capture

import android.content.Context
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackends
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.KeyValueClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.shizuku.ShizukuClipboardBackend
import com.clipsync.android.sync.AndroidSyncLogger
import com.clipsync.android.ui.settings.ClipServices
import com.clipsync.android.ui.settings.LocalCapturePolicy
import com.clipsync.android.ui.wizard.KeyValueWizardSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process singleton holding the production [ClipboardCaptureManager]. Both
 * MainActivity and ClipboardSyncService call [ensureStarted]; whichever runs
 * first builds the stack, so capture works after a boot-started service
 * without the user ever opening the app.
 */
object ClipboardCaptureRuntime {
    private val lock = Any()

    @Volatile
    private var manager: ClipboardCaptureManager? = null

    fun ensureStarted(context: Context): ClipboardCaptureManager {
        val app = context.applicationContext
        val current =
            synchronized(lock) {
                manager ?: createManager(app).also { manager = it }
            }
        current.ensureStarted()
        return current
    }

    /** Live coordinator of the process capture stack, if started. UI status reads it. */
    fun currentAccess(): ClipboardAccessCoordinator? = manager?.access()

    private fun createManager(app: Context): ClipboardCaptureManager =
        ClipboardCaptureManager(
            loadChoices = { KeyValueWizardSettings(SharedPrefsKeyValueStore(app)).load() },
            buildStack = { choices, isVisible ->
                val backends =
                    BackgroundClipboardBackends.build(
                        context = app,
                        isVisible = isVisible,
                        capabilityStore = KeyValueClipboardCapabilityStore(SharedPrefsKeyValueStore(app)),
                        requestedReadMode = choices.preferredReadMode,
                        autoFallbackAllowed = choices.autoFallbackAllowed,
                        overlayConsented = choices.overlayConsented,
                        pollIntervalMillis = choices.pollingIntervalMs.toLong(),
                    )
                ClipServices.writeFallbackProvider = {
                    (backends.shizuku as? ShizukuClipboardBackend)?.fallbackWriter()
                }
                ClipboardCaptureManager.CaptureStack(
                    backends = backends,
                    access = backends.coordinator(),
                    releaseOverlayFocus = backends.overlayController::detach,
                )
            },
            onCapture = { change ->
                val repository = ClipServices.repository(app)
                val writeCoordinator = ClipServices.writeCoordinator(app)
                // Our own writes (inbound apply, History copy, self-test token) echo
                // back through the change listener; they are not user copies.
                val suppressed = writeCoordinator.shouldSuppressCapture(change.text)
                if (!suppressed && !LocalCapturePolicy.isBlocked(repository)) {
                    // PairingStore is the source of truth for peer identity; the
                    // Room mirror is only a UI convenience and can lag it.
                    val peerId =
                        ClipServices
                            .pairingStore(app)
                            .peer()
                            ?.deviceId
                            ?.takeIf { it.isNotBlank() }
                    val sourceTag =
                        captureSourceTag(manager?.access()?.state?.activeReadMode)
                    repository.captureLocalText(
                        change.text,
                        sourceTag,
                        change.observedAtEpochMillis,
                        peerId,
                    )
                    AndroidSyncLogger.event("capture_stored", "background")
                }
            },
            // Main.immediate on purpose: rebuilds and health-loop fallbacks may
            // start the overlay backend, and WindowManager.addView requires the
            // main thread (device-verified MIUI regression when this ran on IO).
            // Capture persistence itself hops to IO inside the manager.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
}

/** Stable lowercase tag for the active listener backend; never a package name. */
internal fun captureSourceTag(activeReadMode: ClipboardReadMode?): String =
    when (activeReadMode) {
        ClipboardReadMode.SHIZUKU_EVENT, null -> "shizuku"
        ClipboardReadMode.ADB_LOG_OVERLAY -> "adb"
        ClipboardReadMode.OVERLAY_POLLING -> "overlay"
        ClipboardReadMode.FOREGROUND_ONLY -> "foreground"
    }
