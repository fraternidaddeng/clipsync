package com.clipsync.android.platform.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R

/**
 * Channel and builder helpers for sync notifications. Per the plan, no notification ever
 * contains clipboard text: the inbox notification shows only the fixed title
 * "来自电脑的新文本" plus a 复制 action that resolves the content by event id inside the app.
 */
object SyncNotifications {
    const val CHANNEL_INBOX = "clipsync.inbox"
    private const val INBOX_NOTIFICATION_ID_BASE = 41_000

    /** Idempotent; called from Application.onCreate so receivers can post right away. */
    fun ensureChannels(context: Context) {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_INBOX,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.notification_channel_inbox_name))
            .setDescription(context.getString(R.string.notification_channel_inbox_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Stable per-event id so re-delivery updates instead of stacking. */
    fun notificationIdFor(eventId: String): Int =
        INBOX_NOTIFICATION_ID_BASE + (eventId.hashCode() and 0x7FFF)

    fun buildInboxItemNotification(context: Context, eventId: String): Notification {
        val requestCode = notificationIdFor(eventId)
        val copyAction = PendingIntent.getBroadcast(
            context,
            requestCode,
            CopyInboxItemReceiver.intent(context, eventId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_INBOX)
            .setSmallIcon(R.drawable.ic_notify_clip)
            // Charter flow blue tints the polyline small icon and the action text
            // on OEMs that honour it; the title/body stay content-free by design.
            .setColor(ContextCompat.getColor(context, R.color.cs_flow))
            .setContentTitle(context.getString(R.string.notification_inbox_title))
            .setContentText(context.getString(R.string.notification_inbox_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            // No clipboard text is present, so the lock screen may show it as-is.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, context.getString(R.string.notification_action_copy), copyAction)
            .build()
    }

    /**
     * Posts (or refreshes) the notification for one inbox item. Returns false when the user has
     * disabled notifications; the item still sits in the inbox, only this surface is missing,
     * which the in-app status card must show separately.
     */
    fun notifyInboxItem(context: Context, eventId: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        return try {
            manager.notify(notificationIdFor(eventId), buildInboxItemNotification(context, eventId))
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
            false
        }
    }

    fun cancelInboxItem(context: Context, eventId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(eventId))
    }
}
