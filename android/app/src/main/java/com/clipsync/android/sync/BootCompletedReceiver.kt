package com.clipsync.android.sync

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.notify.SyncNotifications
import com.clipsync.android.storage.SyncSettingsStore

/**
 * Restores the sync foreground service after a device reboot. Disabled in the manifest until
 * the user turns 开机恢复 on ([setReceiverEnabled]); [BootRestorePolicy] re-checks the
 * preference and the pairing at boot time anyway. Exactly one start attempt is made: a
 * failure becomes the honest "需要恢复" notification, never a retry loop, and
 * [BootHealthCheckWorker] runs a bounded follow-up in case the service silently never
 * reaches RUNNING.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val settings = SyncSettingsStore(
            SharedPrefsKeyValueStore(app, name = SyncSettingsStore.PREFERENCES_NAME),
        )
        val paired = PairingStore(SharedPrefsKeyValueStore(app), KeystoreSecretProtector())
            .peer() != null
        val attempt = BootRestorePolicy.shouldAttemptStart(
            isBootAction = intent?.action == Intent.ACTION_BOOT_COMPLETED,
            bootRestoreEnabled = settings.bootRestoreEnabled,
            serviceEnabled = settings.serviceEnabled,
            paired = paired,
        )
        if (!attempt) {
            return
        }
        // ForegroundServiceStartNotAllowedException (and OEM equivalents) must degrade to a
        // notification the user can act on, not crash the boot broadcast.
        val started = runCatching { ClipboardSyncService.start(app) }.isSuccess
        if (!started) {
            SyncNotifications.notifyRecoveryNeeded(app)
            return
        }
        runCatching { BootHealthCheckWorker.enqueue(app) }
    }

    companion object {
        /**
         * Registers/unregisters the boot receiver to match the 开机恢复 preference (plan 5.2:
         * BOOT_COMPLETED 只在用户打开"开机恢复"后注册). Called whenever the toggle changes.
         */
        fun setReceiverEnabled(context: Context, enabled: Boolean) {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootCompletedReceiver::class.java),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
