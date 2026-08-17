package com.clipsync.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipsync.android.ui.settings.ClipServices

/**
 * Manifest-disabled unless [SETTING_BOOT_RECOVERY_ENABLED] is on. A failed FGS
 * start from boot becomes a recovery notification, never a crash loop.
 * After a start attempt, [BootHealthCheckWorker] runs a bounded check in case
 * the FGS never reaches RUNNING.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val settings = ClipServices.serviceSettings(context)
        val orch = ClipboardSyncRuntime.orchestrator
        orch.setBootRecoveryEnabled(settings.bootRecoveryEnabled())
        orch.wantedRunning = settings.backgroundSyncEnabled()
        if (!orch.bootReceiverShouldBeEnabled()) {
            return
        }
        val app = context.applicationContext
        val outcome = orch.onBootCompleted {
            try {
                ClipboardSyncService.start(context)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (outcome == BootOutcome.RequestUserRecovery) {
            BootRecoveryNotifier.request(app)
        }
        if (BootHealthCheck.shouldEnqueue(outcome)) {
            runCatching { BootHealthCheckWorker.enqueue(app) }
        }
    }

    companion object {
        const val RECOVERY_NOTIFICATION_ID = 0x51C7
    }
}
