package com.clipsync.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R
import com.clipsync.android.notify.SafeNotificationPoster
import com.clipsync.android.ui.settings.ClipServices

/**
 * Manifest-disabled unless [SETTING_BOOT_RECOVERY_ENABLED] is on. A failed FGS
 * start from boot becomes a recovery notification, never a crash loop.
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
        val outcome = orch.onBootCompleted {
            try {
                ClipboardSyncService.start(context)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (outcome == BootOutcome.RequestUserRecovery) {
            requestRecovery(context.applicationContext)
        }
    }

    private fun requestRecovery(app: Context) {
        val poster = SafeNotificationPoster(
            notificationsAllowed = { true },
            post = { postRecoveryNotification(app) },
        )
        runCatching { poster.tryPost() }
    }

    private fun postRecoveryNotification(app: Context) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ServiceNotificationActions.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ServiceNotificationActions.CHANNEL_ID,
                    app.getString(R.string.service_notify_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            app,
            RECOVERY_REQUEST,
            Intent(app, MainActivity::class.java).apply {
                action = ServiceNotificationActions.ACTION_OPEN_STATUS
                putExtra(ServiceNotificationActions.EXTRA_OPEN_TAB, ServiceNotificationActions.TAB_STATUS)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, ServiceNotificationActions.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.service_recovery_title))
            .setContentText(app.getString(R.string.service_recovery_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(RECOVERY_NOTIFICATION_ID, notification)
    }

    companion object {
        const val RECOVERY_NOTIFICATION_ID = 0x51C7
        private const val RECOVERY_REQUEST = 0x51C8
    }
}
