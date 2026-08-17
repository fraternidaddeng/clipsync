package com.clipsync.android.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.clipsync.android.sync.SyncController

/**
 * Process-wide handle for the Stage 5 service. The orchestrator is the source of
 * truth for ownership; this object only holds the live service-scoped controller.
 */
object ClipboardSyncRuntime {
    val orchestrator = ServiceOrchestrator()

    @Volatile
    var serviceController: SyncController? = null
        private set

    fun attachServiceController(controller: SyncController) {
        serviceController = controller
    }

    fun detachServiceController() {
        serviceController = null
    }

    fun activeController(activityController: SyncController?): SyncController? =
        when (orchestrator.controllerOwner) {
            ControllerOwner.SERVICE -> serviceController
            ControllerOwner.ACTIVITY -> activityController
            ControllerOwner.NONE -> null
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
