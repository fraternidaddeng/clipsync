package com.clipsync.android.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R

/**
 * Posts a copy-to-clipboard action for inbound clips when auto-apply is off or
 * the public write failed. Clip bodies are never placed in the notification or logs.
 * [android.Manifest.permission.POST_NOTIFICATIONS] denial must not crash.
 */
class InboundClipNotifier(
    private val context: Context,
    private val permission: NotificationPermission = NotificationPermission(context),
) {
    fun notifyCopyAction(eventId: String) {
        val app = context.applicationContext
        val poster = SafeNotificationPoster(
            notificationsAllowed = { permission.isGranted() },
            post = { postCopyNotification(app, eventId) },
        )
        poster.tryPost()
    }

    fun notifyIfNeeded(eventId: String, autoApplyRemote: Boolean, writeSucceeded: Boolean) {
        if (InboundNotifyPolicy.decide(autoApplyRemote, writeSucceeded) == InboundNotifyDecision.COPY_ACTION) {
            notifyCopyAction(eventId)
        }
    }

    private fun postCopyNotification(app: Context, eventId: String) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val openApp = PendingIntent.getActivity(
            app,
            eventId.hashCode(),
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copy = PendingIntent.getBroadcast(
            app,
            eventId.hashCode() xor COPY_REQUEST_SALT,
            Intent(app, CopyClipReceiver::class.java).putExtra(EXTRA_EVENT_ID, eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.notify_inbound_title))
            .setContentText(app.getString(R.string.notify_inbound_text))
            .setContentIntent(openApp)
            .addAction(
                R.drawable.ic_notification,
                app.getString(R.string.notify_copy_action),
                copy,
            )
            .setAutoCancel(true)
            .build()
        manager.notify(eventId.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notify_channel_description)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "clipsync_inbound"
        const val EXTRA_EVENT_ID = "event_id"
        private const val COPY_REQUEST_SALT = 0x51C0
    }
}
