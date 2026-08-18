package com.clipsync.android.capture

import android.content.Context
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackends
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
                    repository.captureLocalText(
                        change.text,
                        "shizuku",
                        change.observedAtEpochMillis,
                        peerId,
                    )
                    AndroidSyncLogger.event("capture_stored", "background")
                }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
}
