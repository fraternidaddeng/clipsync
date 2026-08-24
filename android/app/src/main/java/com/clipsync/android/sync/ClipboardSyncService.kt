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
import com.clipsync.android.R
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the outbound sync session to the paired Windows peer alive.
 * The notification only ever names the connection state; it never contains clipboard text
 * (threat model: notifications are visible on the lock screen).
 *
 * Wiring: the [SyncSupervisor] dials and reconnects, the [SyncEngine] speaks protocol v1, the
 * Room-backed [ClipSyncRepository] persists everything, share-sheet/tile entries land through
 * [SyncServices] and are drained into the store here, and committed remote clips flow out to
 * the inbox notification via [InboxDelivery].
 */
class ClipboardSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncNudges = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mutableServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(notification(text = STATE_CONNECTING))
        if (!started) {
            started = true
            launchSyncStack()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mutableServiceRunning.value = false
        mutableConnectionStates.value = SyncConnectionState.NotPaired
        scope.cancel()
        super.onDestroy()
    }

    private fun launchSyncStack() {
        val appContext = applicationContext
        val pairing = PairingStore(SharedPrefsKeyValueStore(appContext), KeystoreSecretProtector())
        val repository = repositoryProvider(appContext)

        // System entry points (share target, Quick Settings tile) nudge the drain below.
        SyncServices.initialize(appContext)
        SyncServices.install(
            outbox = SyncServices.outbox,
            inbox = SyncServices.inbox,
            syncRequester = { syncNudges.trySend(Unit) },
        )

        val supervisor = SyncSupervisor(
            pairing = pairing,
            repository = repository,
            connector = OkHttpSyncConnector(),
            clientVersion = clientVersion(),
            onRemoteClipsCommitted = { committed ->
                committed.forEach { applied ->
                    InboxDelivery.deliver(appContext, applied.eventId, applied.content)
                }
            },
        )
        scope.launch { supervisor.run() }
        scope.launch {
            supervisor.state.collect { state ->
                mutableConnectionStates.value = state
                updateNotification(state)
            }
        }
        scope.launch {
            drainShareOutbox(repository) // catch up entries queued while the service was down
            for (nudge in syncNudges) {
                drainShareOutbox(repository)
            }
        }
    }

    /**
     * Moves share-sheet/tile entries from the [SyncServices] queue into the Room store, which
     * allocates the origin sequence and fans the event out to the paired peer's outbox.
     */
    private suspend fun drainShareOutbox(repository: SyncRepository) {
        for (entry in SyncServices.outbox.pending()) {
            repository.recordLocalClip(
                text = entry.text,
                sourceApp = sourceLabel(entry.source),
                nowMs = entry.createdAtEpochMillis,
            )
            // Oversized/empty entries return null above; they are dropped rather than retried
            // forever, matching the enqueue-side rules that should have rejected them already.
            SyncServices.outbox.remove(entry.eventId)
        }
    }

    private fun sourceLabel(source: ClipSource): String = when (source) {
        ClipSource.SHARE_SHEET -> "android.share_sheet"
        ClipSource.QUICK_TILE -> "android.quick_tile"
        ClipSource.FOREGROUND_APP -> "android.app"
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
            .setSmallIcon(R.drawable.ic_notify_clip)
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

        private const val STATE_CONNECTED = "ClipSync 已连接"
        private const val STATE_CONNECTING = "ClipSync 正在连接…"
        private const val STATE_RECONNECTING = "ClipSync 等待重连"
        private const val STATE_NOT_PAIRED = "ClipSync 未配对"

        private val mutableServiceRunning = MutableStateFlow(false)
        private val mutableConnectionStates = MutableStateFlow<SyncConnectionState>(SyncConnectionState.NotPaired)

        /** Whether the foreground service is alive; feeds the conduit's SyncHealthSource. */
        val serviceRunning: StateFlow<Boolean> = mutableServiceRunning.asStateFlow()

        /** Live connection state mirror for UI surfaces; never carries clipboard text. */
        val connectionStates: StateFlow<SyncConnectionState> = mutableConnectionStates.asStateFlow()

        @Volatile
        private var sharedRepository: SyncRepository? = null

        /** Replaceable seam so tests can swap the Room-backed store. */
        var repositoryProvider: (Context) -> SyncRepository = { context ->
            sharedRepository ?: synchronized(ClipboardSyncService::class.java) {
                sharedRepository ?: createRoomRepository(context.applicationContext)
                    .also { sharedRepository = it }
            }
        }

        private fun createRoomRepository(appContext: Context): SyncRepository {
            val pairing = PairingStore(SharedPrefsKeyValueStore(appContext), KeystoreSecretProtector())
            return RoomSyncRepository(
                store = ClipSyncRepository(ClipSyncDatabase.build(appContext), pairing.localDeviceId()),
                fanOutPeerIds = { pairing.peer()?.deviceId?.let(::listOf).orEmpty() },
            )
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ClipboardSyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardSyncService::class.java))
        }
    }
}
