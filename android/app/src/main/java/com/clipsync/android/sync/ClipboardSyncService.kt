package com.clipsync.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clipsync.android.R
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.notify.SyncNotifications
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * [SyncServices] and are drained into the store here, and committed remote clips flow out
 * through [InboxDelivery]: inbox first, then auto-apply to the system clipboard when the
 * auto_apply_remote preference is on. A [ConnectivityManager] network callback nudges the
 * supervisor when a network comes up so reconnects do not wait out the full backoff.
 */
class ClipboardSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncNudges = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private var supervisor: SyncSupervisor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var settings: SyncSettingsStore

    // Strong reference: SharedPreferences keeps listeners weakly. Fires when a settings
    // write actually changed a value — from the notification actions or the preferences
    // screen — so the pause action labels/status line never go stale.
    private var settingsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settings = SyncSettingsStore(
            SharedPrefsKeyValueStore(applicationContext, name = SyncSettingsStore.PREFERENCES_NAME),
        )
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (started) {
                updateNotification(mutableConnectionStates.value)
            }
        }
        settingsListener = listener
        applicationContext
            .getSharedPreferences(SyncSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
        mutableServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Apply a notification action first so the notification promoted below is honest.
        if (SyncServiceNotification.applyAction(intent?.action, settings)) {
            requestSyncNow()
        }
        // The system can refuse the foreground promotion (e.g. FGS-from-background policy on
        // some OEM/API combinations). That must degrade to the honest "需要恢复" notification
        // and a stopped service — never a crash, never a sticky restart loop (plan 5.2).
        val stateText = if (started) {
            stateText(mutableConnectionStates.value)
        } else {
            getString(R.string.notification_sync_connecting)
        }
        val foregroundOk = runCatching {
            startAsForeground(notification(stateText))
        }.isSuccess
        if (!foregroundOk) {
            mutableStartErrorCodes.value = START_ERROR_FGS_DENIED
            SyncNotifications.notifyRecoveryNeeded(applicationContext)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        mutableStartErrorCodes.value = null
        SyncNotifications.cancelRecoveryNeeded(applicationContext)
        if (!started) {
            started = true
            launchSyncStack()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        settingsListener?.let { listener ->
            applicationContext
                .getSharedPreferences(SyncSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(listener)
        }
        settingsListener = null
        unregisterNetworkCallback()
        mutableServiceRunning.value = false
        mutableConnectionStates.value = SyncConnectionState.NotPaired
        mutablePeerThrottled.value = false
        scope.cancel()
        super.onDestroy()
    }

    /** 立即同步: drain queued share/tile entries and cut any reconnect backoff short. */
    private fun requestSyncNow() {
        syncNudges.trySend(Unit)
        supervisor?.nudgeReconnect()
    }

    private fun launchSyncStack() {
        val appContext = applicationContext
        val pairing = PairingStore(SharedPrefsKeyValueStore(appContext), KeystoreSecretProtector())
        val repository = repositoryProvider(appContext)
        val settings = this.settings
        val mainHandler = Handler(Looper.getMainLooper())

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
                // Clipboard writes run on the main thread (mirrors the Windows dispatcher hop).
                mainHandler.post {
                    // Preferences are re-read per batch so toggling applies immediately.
                    // Paused sync still receives into the inbox but never auto-applies.
                    // Like Windows, only the newest body of a batch reaches the system
                    // clipboard; every event still lands in the inbox first. Images have
                    // their own opt-in write gate (ADR 0004), independent of the text one.
                    val autoApply = InboxDelivery.autoApplyAllowed(settings)
                    val autoApplyImage = InboxDelivery.autoApplyImagesAllowed(settings)
                    val newestEventId = committed.lastOrNull()?.eventId
                    committed.forEach { applied ->
                        if (applied.isImage) {
                            InboxDelivery.deliverImage(
                                appContext,
                                applied.eventId,
                                applied.contentHash,
                                applied.mimeType,
                                autoApply = autoApplyImage && applied.eventId == newestEventId,
                            )
                        } else {
                            InboxDelivery.deliver(
                                appContext,
                                applied.eventId,
                                applied.content,
                                autoApply = autoApply && applied.eventId == newestEventId,
                            )
                        }
                    }
                }
            },
            // Pause/private stop outbound announces immediately; re-read every drain tick.
            outboundAllowed = { !settings.syncPaused && !settings.privateMode },
            // With the preference on, dial protocol v2 first and fall back to v1 listeners.
            imageSyncEnabled = { settings.imageSyncEnabled },
            // The peer rate-limited this device after repeated failed auth: mirror the
            // Windows tray bubble with a content-free notification plus a conduit fact.
            onAuthThrottled = {
                mutablePeerThrottled.value = true
                SyncNotifications.notifyAuthThrottled(appContext)
            },
        )
        this.supervisor = supervisor
        // Network-available events cut the reconnect backoff short (plan: "网络恢复后立即触发
        // 一次重连") instead of letting a fresh link sit out a wait that can reach 60 s.
        registerNetworkCallback()
        scope.launch { supervisor.run() }
        scope.launch {
            supervisor.state.collect { state ->
                if (state is SyncConnectionState.Connected && mutablePeerThrottled.value) {
                    // Authenticated again: the lockout episode is over on both surfaces.
                    mutablePeerThrottled.value = false
                    SyncNotifications.cancelAuthThrottled(appContext)
                }
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
        scope.launch {
            // Retention cleanup on start and periodically after (Windows runs the same policy
            // on startup and on settings save). The policy is re-read per pass so preference
            // changes apply without a service restart.
            val store = SyncStore.repository(appContext)
            while (true) {
                runCatching {
                    store.cleanup(settings.effectiveRetentionPolicy(), System.currentTimeMillis())
                }
                delay(RETENTION_CLEANUP_INTERVAL_MS)
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

    /**
     * Watches for any network that offers internet and nudges the supervisor when one appears,
     * so reconnects happen at network-recovery time rather than after the remaining backoff.
     * Registration failures (e.g. an OEM's callback quota) are tolerated: the supervisor's
     * normal backoff still reconnects on its own, just without the early trigger.
     */
    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                supervisor?.nudgeReconnect()
            }
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { manager.unregisterNetworkCallback(callback) }
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // POST_NOTIFICATIONS may be revoked on API 33+; the service keeps running regardless.
        runCatching { manager.notify(NOTIFICATION_ID, notification(stateText(state))) }
    }

    private fun stateText(state: SyncConnectionState): String = when (state) {
        is SyncConnectionState.Connected -> getString(R.string.notification_sync_connected)
        is SyncConnectionState.Connecting -> getString(R.string.notification_sync_connecting)
        is SyncConnectionState.WaitingRetry -> getString(R.string.notification_sync_reconnecting)
        is SyncConnectionState.NotPaired -> getString(R.string.notification_sync_not_paired)
    }

    /**
     * The resident notification: connection state as the title, pause status as the text,
     * the plan 5.2 actions as buttons, 打开故障状态 (通路) on tap. Never clipboard content.
     */
    private fun notification(text: String): Notification =
        SyncServiceNotification.build(
            this,
            channelId = CHANNEL_ID,
            stateText = text,
            syncPaused = settings.syncPaused,
            autoCapturePaused = settings.autoCapturePaused,
        )

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_sync_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_sync_description)
            // A resident state notification must not add a launcher badge.
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun clientVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "0.0.0"

    companion object {
        private const val CHANNEL_ID = "clipsync.sync"
        private const val NOTIFICATION_ID = 1001

        /** Stable code the conduit shows when the foreground promotion was refused. */
        const val START_ERROR_FGS_DENIED = "FGS_START_DENIED"

        /** How often the retention policy is enforced while the service is alive. */
        private const val RETENTION_CLEANUP_INTERVAL_MS = 6L * 60 * 60 * 1_000

        private val mutableServiceRunning = MutableStateFlow(false)
        private val mutableConnectionStates = MutableStateFlow<SyncConnectionState>(SyncConnectionState.NotPaired)
        private val mutableStartErrorCodes = MutableStateFlow<String?>(null)
        private val mutablePeerThrottled = MutableStateFlow(false)

        /** Whether the foreground service is alive; feeds the conduit's SyncHealthSource. */
        val serviceRunning: StateFlow<Boolean> = mutableServiceRunning.asStateFlow()

        /** Live connection state mirror for UI surfaces; never carries clipboard text. */
        val connectionStates: StateFlow<SyncConnectionState> = mutableConnectionStates.asStateFlow()

        /** Last foreground-start failure code, or null; cleared on the next successful start. */
        val startErrorCodes: StateFlow<String?> = mutableStartErrorCodes.asStateFlow()

        /**
         * True while the paired peer is rate-limiting this device after repeated failed
         * authentication; cleared when a session authenticates. Feeds the conduit page.
         */
        val peerThrottled: StateFlow<Boolean> = mutablePeerThrottled.asStateFlow()

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
            val settings = SyncSettingsStore(
                SharedPrefsKeyValueStore(appContext, name = SyncSettingsStore.PREFERENCES_NAME),
            )
            return RoomSyncRepository(
                store = SyncStore.repository(appContext),
                fanOutPeerIds = { pairing.peer()?.deviceId?.let(::listOf).orEmpty() },
                // Re-read per clip so the user cap applies without restarting the service.
                maxContentUtf8Bytes = { settings.effectiveMaxSyncTextBytes },
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
