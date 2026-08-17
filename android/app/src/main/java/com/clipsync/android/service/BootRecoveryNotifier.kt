package com.clipsync.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R
import com.clipsync.android.notify.SafeNotificationPoster

/**
 * Posts the existing recovery notification. Never includes clipboard text.
 */
object BootRecoveryNotifier {
    const val RECOVERY_NOTIFICATION_ID = BootCompletedReceiver.RECOVERY_NOTIFICATION_ID
    private const val RECOVERY_REQUEST = 0x51C8

    fun request(app: Context) {
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
}
