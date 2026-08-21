package com.clipsync.android.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.clipsync.android.notify.InboundClip
import com.clipsync.android.notify.InboundClipApplier
import com.clipsync.android.notify.InboundClipNotifier
import com.clipsync.android.protocol.ProtocolLimits
import com.clipsync.android.sync.AndroidSyncLogger
import com.clipsync.android.sync.SyncController
import com.clipsync.android.sync.SyncSessionOptions
import com.clipsync.android.sync.createSyncController
import com.clipsync.android.ui.settings.ClipServices
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide handle for the Stage 5 service plus the single process-scoped
 * [SyncController]. There is exactly one controller per process; Activity and
 * Service both start/stop the same instance, so the old Activity<->Service
 * handover (and its resubscription races) no longer exists.
 */
object ClipboardSyncRuntime {
    val orchestrator = ServiceOrchestrator()

    private val lock = Any()
    private val activityCount = AtomicInteger(0)

    @Volatile
    private var controller: SyncController? = null

    fun controller(context: Context): SyncController {
        val app = context.applicationContext
        return controller ?: synchronized(lock) {
            controller ?: createController(app).also { controller = it }
        }
    }

    fun controllerOrNull(): SyncController? = controller

    fun noteActivityCreated() {
        activityCount.incrementAndGet()
    }

    fun noteActivityDestroyed() {
        activityCount.updateAndGet { count -> maxOf(0, count - 1) }
    }

    /**
     * Stops the controller when nobody needs it anymore: no Activity alive and
     * background sync not wanted. Keeps the old behavior where sync died with
     * the Activity when the foreground service was off.
     */
    fun stopControllerIfUnneeded() {
        if (activityCount.get() == 0 && !orchestrator.wantedRunning) {
            controller?.stop()
        }
    }

    private fun createController(app: Context): SyncController {
        val repository = ClipServices.repository(app)
        val writeCoordinator = ClipServices.writeCoordinator(app)
        val notifier = InboundClipNotifier(app)
        val applier = InboundClipApplier(repository, writeCoordinator) { eventId ->
            notifier.notifyCopyAction(eventId)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return createSyncController(
            pairingStore = ClipServices.pairingStore(app),
            repository = repository,
            scope = scope,
            options = SyncSessionOptions(protocolVersion = ProtocolLimits.PROTOCOL_VERSION_V2),
            logger = AndroidSyncLogger,
            onRemoteClipsCommitted = { clips ->
                scope.launch(Dispatchers.IO) {
                    applier.onCommitted(
                        clips.map {
                            InboundClip(
                                eventId = it.eventId,
                                content = it.content,
                                kind = it.kind,
                                contentHash = it.contentHash,
                                mimeType = it.mimeType,
                            )
                        },
                    )
                }
            },
        )
    }

    fun applyBootReceiverEnabled(context: Context, enabled: Boolean) {
        orchestrator.setBootRecoveryEnabled(enabled)
        val component = ComponentName(context, BootCompletedReceiver::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}
