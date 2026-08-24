package com.clipsync.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the outbound sync session to the paired Windows peer alive.
 * The notification only ever names the connection state; it never contains clipboard text
 * (threat model: notifications are visible on the lock screen).
 */
class ClipboardSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var supervisor: SyncSupervisor? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground(notification(text = STATE_CONNECTING))
        if (!started) {
            started = true
            val pairing = PairingStore(SharedPrefsKeyValueStore(this), KeystoreSecretProtector())
            val running = SyncSupervisor(
                pairing = pairing,
                repository = repositoryProvider(this),
                connector = OkHttpSyncConnector(),
                clientVersion = clientVersion(),
            )
            supervisor = running
            scope.launch { running.run() }
            scope.launch {
                running.state.collect { state -> updateNotification(state) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        // minSdk is 29, where the connectedDevice foreground service type already exists.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(state: SyncConnectionState) {
        val text = when (state) {
            is SyncConnectionState.Connected -> STATE_CONNECTED
            is SyncConnectionState.Connecting -> STATE_CONNECTING
            is SyncConnectionState.WaitingRetry -> STATE_RECONNECTING
            is SyncConnectionState.NotPaired -> STATE_NOT_PAIRED
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun clientVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "0.0.0"

    companion object {
        private const val CHANNEL_ID = "clipsync.sync"
        private const val CHANNEL_NAME = "同步状态"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.clipsync.android.sync.STOP"

        private const val STATE_CONNECTED = "ClipSync 已连接"
        private const val STATE_CONNECTING = "ClipSync 正在连接…"
        private const val STATE_RECONNECTING = "ClipSync 等待重连"
        private const val STATE_NOT_PAIRED = "ClipSync 未配对"

        /**
         * Repository shared between the service and (later) the capture pipeline. Replaced by
         * the Room-backed implementation once it lands; the seam is this provider.
         */
        @Volatile
        private var sharedRepository: SyncRepository? = null

        var repositoryProvider: (Context) -> SyncRepository = { context ->
            sharedRepository ?: synchronized(ClipboardSyncService::class.java) {
                sharedRepository ?: InMemorySyncRepository(
                    PairingStore(
                        SharedPrefsKeyValueStore(context.applicationContext),
                        KeystoreSecretProtector(),
                    ).localDeviceId(),
                ).also { sharedRepository = it }
            }
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ClipboardSyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardSyncService::class.java))
        }
    }
}
