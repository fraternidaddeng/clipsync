package com.clipsync.android.platform.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R

/**
 * Channel and builder helpers for sync notifications. Per the plan, no notification ever
 * contains clipboard text: the inbox notification shows only the fixed title
 * "来自电脑的新文本" plus a 复制 action that resolves the content by event id inside the app.
 *
 * All channels live under one 剪贴同步 group so the app's settings page reads as a single
 * charter surface instead of three stray entries, and every builder wears the same accents:
 * the polyline small icon plus flow blue (#215F8F) via setColor.
 */
object SyncNotifications {
    const val GROUP_ID = "clipsync"
    const val CHANNEL_SYNC = "clipsync.sync"
    const val CHANNEL_INBOX = "clipsync.inbox"
    const val CHANNEL_RECOVERY = "clipsync.recovery"
    private const val INBOX_NOTIFICATION_ID_BASE = 41_000

    // The per-event inbox ids span [41_000, 41_000 + 0x7FFF] = [41_000, 73_767]; the fixed
    // ids below must stay outside that range or an unlucky event-id hash replaces (and
    // cancelInboxItem cancels) the recovery / auth-throttle / flood-summary notification.
    const val RECOVERY_NOTIFICATION_ID = 74_001
    const val AUTH_THROTTLE_NOTIFICATION_ID = 74_002
    const val INBOX_FLOOD_NOTIFICATION_ID = 74_003

    /** Idempotent; called from Application.onCreate so receivers can post right away. */
    fun ensureChannels(context: Context) {
        ensureGroup(context)
        val channel =
            NotificationChannelCompat
                .Builder(
                    CHANNEL_INBOX,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                ).setName(context.getString(R.string.notification_channel_inbox_name))
                .setDescription(context.getString(R.string.notification_channel_inbox_description))
                .setGroup(GROUP_ID)
                .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
        ensureSyncChannel(context)
        ensureRecoveryChannel(context)
    }

    /** One 剪贴同步 group holds every channel; must exist before any channel names it. */
    private fun ensureGroup(context: Context) {
        val group =
            NotificationChannelGroupCompat
                .Builder(GROUP_ID)
                .setName(context.getString(R.string.notification_group_name))
                .setDescription(context.getString(R.string.notification_group_description))
                .build()
        NotificationManagerCompat.from(context).createNotificationChannelGroup(group)
    }

    /**
     * The resident foreground-service channel. Low importance: a persistent state line must
     * never buzz, and it must not add a launcher badge. The service re-ensures it before
     * promoting, so an FGS start never races Application.onCreate.
     */
    fun ensureSyncChannel(context: Context) {
        ensureGroup(context)
        val channel =
            NotificationChannelCompat
                .Builder(
                    CHANNEL_SYNC,
                    NotificationManagerCompat.IMPORTANCE_LOW,
                ).setName(context.getString(R.string.notification_channel_sync_name))
                .setDescription(context.getString(R.string.notification_channel_sync_description))
                .setGroup(GROUP_ID)
                .setShowBadge(false)
                .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private fun ensureRecoveryChannel(context: Context) {
        ensureGroup(context)
        val channel =
            NotificationChannelCompat
                .Builder(
                    CHANNEL_RECOVERY,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                ).setName(context.getString(R.string.notification_channel_recovery_name))
                .setDescription(context.getString(R.string.notification_channel_recovery_description))
                .setGroup(GROUP_ID)
                .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Stable per-event id so re-delivery updates instead of stacking. */
    fun notificationIdFor(eventId: String): Int = INBOX_NOTIFICATION_ID_BASE + (eventId.hashCode() and 0x7FFF)

    fun buildInboxItemNotification(
        context: Context,
        eventId: String,
    ): Notification {
        val requestCode = notificationIdFor(eventId)
        val copyAction =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                CopyInboxItemReceiver.intent(context, eventId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val openApp =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_INBOX)
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
    fun notifyInboxItem(
        context: Context,
        eventId: String,
    ): Boolean {
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

    /**
     * Content-free arrival card for a remote image that stayed in history (auto-apply off,
     * skipped, or failed): plan 5.6 honesty — received content never lands silently. No
     * pixels, no hash, and no 复制 action (the text inbox cannot resolve an image); the card
     * only states the arrival and opens the app, where history holds the thumbnail.
     */
    fun buildInboxImageNotification(
        context: Context,
        eventId: String,
    ): Notification {
        val requestCode = notificationIdFor(eventId)
        val openApp =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_INBOX)
            .setSmallIcon(R.drawable.ic_notify_clip)
            .setColor(ContextCompat.getColor(context, R.color.cs_flow))
            .setContentTitle(context.getString(R.string.notification_inbox_image_title))
            .setContentText(context.getString(R.string.notification_inbox_image_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            // No clipboard content is present, so the lock screen may show it as-is.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    /** Image counterpart of [notifyInboxItem]; same honest degradation contract. */
    fun notifyInboxImage(
        context: Context,
        eventId: String,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        return try {
            manager.notify(notificationIdFor(eventId), buildInboxImageNotification(context, eventId))
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
            false
        }
    }

    /**
     * Posts the content-free status notification for a remote clip that was auto-applied to
     * the system clipboard (plan 阶段 4: 成功后再发不含正文的状态通知). It shares the event's
     * notification id, so it replaces any earlier copy-action notification for the same event.
     */
    fun notifyAutoApplied(
        context: Context,
        eventId: String,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        val requestCode = notificationIdFor(eventId)
        val openApp =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_INBOX)
                .setSmallIcon(R.drawable.ic_notify_clip)
                .setColor(ContextCompat.getColor(context, R.color.cs_flow))
                .setContentTitle(context.getString(R.string.notification_inbox_title))
                .setContentText(context.getString(R.string.notification_applied_text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        return try {
            manager.notify(requestCode, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancelInboxItem(
        context: Context,
        eventId: String,
    ) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(eventId))
    }

    /**
     * The coalesced inbox notification (hardening: notification flood cap): once the
     * per-window budget of per-event notifications is spent, one counting card stands in
     * for the rest of the burst. Count updates replace it in place without re-alerting;
     * like every other notification it names no clipboard content.
     */
    fun notifyInboxFlood(
        context: Context,
        suppressedInWindow: Int,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        val openApp =
            PendingIntent.getActivity(
                context,
                INBOX_FLOOD_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_INBOX)
                .setSmallIcon(R.drawable.ic_notify_clip)
                .setColor(ContextCompat.getColor(context, R.color.cs_flow))
                .setContentTitle(context.getString(R.string.notification_inbox_flood_title))
                .setContentText(
                    context.resources.getQuantityString(
                        R.plurals.notification_inbox_flood_text,
                        suppressedInWindow,
                        suppressedInWindow,
                    ),
                ).setNumber(suppressedInWindow)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                // No clipboard text is present, so the lock screen may show it as-is.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        return try {
            manager.notify(INBOX_FLOOD_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
            false
        }
    }

    /**
     * Posts the honest "需要恢复" notification: the sync service could not be (re)started
     * automatically — after boot, or when the system refused the foreground start — and the
     * user must open the app to restore it. Never restarts anything itself (plan 5.2: no
     * crash loop, no fake "online"). Returns false when notifications are disabled; the
     * conduit page still shows the same fact in-app.
     */
    fun notifyRecoveryNeeded(context: Context): Boolean {
        ensureRecoveryChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        val openApp =
            PendingIntent.getActivity(
                context,
                RECOVERY_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_RECOVERY)
                .setSmallIcon(R.drawable.ic_notify_clip)
                .setColor(ContextCompat.getColor(context, R.color.cs_flow))
                .setContentTitle(context.getString(R.string.notification_recovery_title))
                .setContentText(context.getString(R.string.notification_recovery_text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                // No clipboard text is present, so the lock screen may show it as-is.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        return try {
            manager.notify(RECOVERY_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
            false
        }
    }

    fun cancelRecoveryNeeded(context: Context) {
        NotificationManagerCompat.from(context).cancel(RECOVERY_NOTIFICATION_ID)
    }

    /**
     * Warns that the paired Windows peer temporarily rate-limited this device after repeated
     * failed authentication (mirrors the Windows tray bubble for the same event). Fired once
     * per lockout episode by the sync supervisor; cancelled when a session authenticates.
     * Contains no clipboard content and no proof material. Returns false when the user has
     * disabled notifications — the conduit page states the same fact in-app.
     */
    fun notifyAuthThrottled(context: Context): Boolean {
        ensureRecoveryChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        val openApp =
            PendingIntent.getActivity(
                context,
                AUTH_THROTTLE_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_RECOVERY)
                .setSmallIcon(R.drawable.ic_notify_clip)
                .setColor(ContextCompat.getColor(context, R.color.cs_flow))
                .setContentTitle(context.getString(R.string.notification_auth_throttled_title))
                .setContentText(context.getString(R.string.notification_auth_throttled_text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                // No clipboard text is present, so the lock screen may show it as-is.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        return try {
            manager.notify(AUTH_THROTTLE_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
            false
        }
    }

    fun cancelAuthThrottled(context: Context) {
        NotificationManagerCompat.from(context).cancel(AUTH_THROTTLE_NOTIFICATION_ID)
    }
}
