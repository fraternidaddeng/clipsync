package com.clipsync.android.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R
import com.clipsync.android.storage.SyncSettingsStore

/**
 * The resident foreground-service notification with the plan 5.2 actions: 暂停全部同步 /
 * 仅暂停自动捕获 / 立即同步 as buttons, and 打开故障状态 as the content tap (the 通路 tab).
 * The two pause buttons flip to 恢复 labels while their gate is on. Titles, status lines,
 * and action labels are fixed strings — no clipboard content ever reaches this surface
 * (threat model: the notification is visible on the lock screen).
 */
object SyncServiceNotification {
    const val ACTION_PAUSE_ALL = "com.clipsync.android.action.PAUSE_ALL_SYNC"
    const val ACTION_RESUME_ALL = "com.clipsync.android.action.RESUME_ALL_SYNC"
    const val ACTION_PAUSE_CAPTURE = "com.clipsync.android.action.PAUSE_AUTO_CAPTURE"
    const val ACTION_RESUME_CAPTURE = "com.clipsync.android.action.RESUME_AUTO_CAPTURE"
    const val ACTION_SYNC_NOW = "com.clipsync.android.action.SYNC_NOW"

    private const val REQUEST_OPEN_CONDUIT = 1
    private const val REQUEST_PAUSE_TOGGLE = 2
    private const val REQUEST_CAPTURE_TOGGLE = 3
    private const val REQUEST_SYNC_NOW = 4

    /**
     * Applies one notification action to the settings store. Returns true when the action
     * asks for an immediate sync attempt (the service nudges its supervisor/outbox drain);
     * unknown or null actions change nothing. Pure so JVM tests cover every mapping.
     */
    fun applyAction(
        action: String?,
        settings: SyncSettingsStore,
    ): Boolean =
        when (action) {
            ACTION_PAUSE_ALL -> {
                settings.syncPaused = true
                false
            }
            ACTION_RESUME_ALL -> {
                settings.syncPaused = false
                // Resuming should show results promptly, not at the next drain/backoff tick.
                true
            }
            ACTION_PAUSE_CAPTURE -> {
                settings.autoCapturePaused = true
                false
            }
            ACTION_RESUME_CAPTURE -> {
                settings.autoCapturePaused = false
                false
            }
            ACTION_SYNC_NOW -> true
            else -> false
        }

    // Six parameters, all facts of the one resident line; a holder type would
    // only rename the same list.
    @Suppress("LongParameterList")
    fun build(
        context: Context,
        channelId: String,
        stateText: String,
        syncPaused: Boolean,
        autoCapturePaused: Boolean,
        /**
         * True while the coordinator's active read route is the polling overlay
         * (plan 5.5): the resident line must state that polling is running, so
         * the user can explain the focus flicker and the battery cost — and the
         * pause action right beside it is the promised way out.
         */
        overlayPolling: Boolean = false,
    ): Notification {
        // The system template is deliberate: per DESIGN-CHARTER.md, notifications are drawn
        // by the system (MIUI rewrites them again), so the charter surface here is exactly
        // the polyline small icon, flow blue via setColor, the fixed Chinese copy, and the
        // action row — never a custom RemoteViews layout that OEM shades break.
        val builder =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notify_clip)
                // Flow blue accents the polyline mark where the OEM honours setColor.
                .setColor(ContextCompat.getColor(context, R.color.cs_flow))
                .setContentTitle(stateText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // Mirrors the channel's IMPORTANCE_LOW: a resident state line never buzzes.
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // A state line is "now" by definition; a stale clock reads like noise.
                .setShowWhen(false)
                // Content-free by design, so the lock screen may show it as-is.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openConduitIntent(context))

        // Pause facts outrank the polling fact: while either gate is on, no
        // backend polls at all, so claiming "轮询进行中" would be a lie.
        val statusLine =
            when {
                syncPaused -> context.getString(R.string.notification_status_sync_paused)
                autoCapturePaused -> context.getString(R.string.notification_status_capture_paused)
                overlayPolling -> context.getString(R.string.notification_status_overlay_polling)
                else -> null
            }
        if (statusLine != null) {
            builder.setContentText(statusLine)
        }

        if (syncPaused) {
            builder.addAction(
                0,
                context.getString(R.string.notification_action_resume_all),
                serviceIntent(context, REQUEST_PAUSE_TOGGLE, ACTION_RESUME_ALL),
            )
        } else {
            builder.addAction(
                0,
                context.getString(R.string.notification_action_pause_all),
                serviceIntent(context, REQUEST_PAUSE_TOGGLE, ACTION_PAUSE_ALL),
            )
        }
        if (autoCapturePaused) {
            builder.addAction(
                0,
                context.getString(R.string.notification_action_resume_capture),
                serviceIntent(context, REQUEST_CAPTURE_TOGGLE, ACTION_RESUME_CAPTURE),
            )
        } else {
            builder.addAction(
                0,
                context.getString(R.string.notification_action_pause_capture),
                serviceIntent(context, REQUEST_CAPTURE_TOGGLE, ACTION_PAUSE_CAPTURE),
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.notification_action_sync_now),
            serviceIntent(context, REQUEST_SYNC_NOW, ACTION_SYNC_NOW),
        )
        return builder.build()
    }

    /** 打开故障状态: the conduit (通路) tab is the app's fault/status surface. */
    private fun openConduitIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN_CONDUIT,
            MainActivity.conduitIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Actions run through the already-running foreground service. getForegroundService keeps
     * the delivery legal even when the tap arrives with the app otherwise in the background.
     */
    private fun serviceIntent(
        context: Context,
        requestCode: Int,
        action: String,
    ): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            Intent(context, ClipboardSyncService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
