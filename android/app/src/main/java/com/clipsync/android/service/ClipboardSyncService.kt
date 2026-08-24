package com.clipsync.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The `connectedDevice` foreground service of plan §5.2 in its stage-4 skeleton: it keeps the
 * process alive for connection scheduling and reports an honest running/failed state to the
 * conduit page. It holds NO clipboard privilege (plan §0.1.2 rule 6) and no sync connection
 * yet — the WebSocket session lands with the sync stage. Start failures surface as stable
 * error codes instead of crashes; the UI shows the missing piece.
 */
class ClipboardSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            mutableRunning.value = true
            mutableLastErrorCode.value = null
            START_STICKY
        } catch (exception: Exception) {
            // Android 14+ throws when the FGS type or its permission is missing; some OEMs
            // reject the start outright. Record a stable code so the conduit page can show
            // the concrete missing piece instead of a vague "sync failed".
            mutableLastErrorCode.value = when (exception) {
                is SecurityException -> ERROR_START_REJECTED
                else -> ERROR_TYPE_MISSING
            }
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        mutableRunning.value = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "同步服务", NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Never any clipboard content in the notification (plan §5.2).
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_conduit)
            .setContentTitle("同步服务运行中")
            .setContentText("保持与已配对电脑的连接调度；不代表剪贴板可读写")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "clipsync.sync"
        private const val NOTIFICATION_ID = 100
        private const val ACTION_STOP = "com.clipsync.android.action.STOP_SYNC_SERVICE"

        const val ERROR_START_REJECTED = "FGS_START_REJECTED"
        const val ERROR_TYPE_MISSING = "FGS_TYPE_MISSING"

        private val mutableRunning = MutableStateFlow(false)
        private val mutableLastErrorCode = MutableStateFlow<String?>(null)

        /** True while the service holds its foreground notification; never faked (plan §5.2). */
        val running: StateFlow<Boolean> = mutableRunning.asStateFlow()
        val lastErrorCode: StateFlow<String?> = mutableLastErrorCode.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClipboardSyncService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ClipboardSyncService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
