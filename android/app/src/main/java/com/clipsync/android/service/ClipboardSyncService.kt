package com.clipsync.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clipsync.android.MainActivity
import com.clipsync.android.R
import com.clipsync.android.notify.InboundClip
import com.clipsync.android.notify.InboundClipApplier
import com.clipsync.android.notify.InboundClipNotifier
import com.clipsync.android.notify.NotificationPermission
import com.clipsync.android.sync.SyncController
import com.clipsync.android.sync.createSyncController
import com.clipsync.android.ui.settings.ClipServices
import com.clipsync.android.ui.settings.SETTING_IS_PAUSED
import com.clipsync.android.ui.settings.formatSettingFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Thin connectedDevice ForegroundService. Logic lives in [ServiceOrchestrator].
 * Owning this service is not clipboard permission.
 */
class ClipboardSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: SyncController? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val orch = ClipboardSyncRuntime.orchestrator
        when (intent?.action) {
            ServiceNotificationActions.ACTION_STOP -> {
                stopOwnedController()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ServiceNotificationActions.ACTION_PAUSE_ALL -> {
                scope.launch(Dispatchers.IO) {
                    ClipServices.repository(this@ClipboardSyncService)
                        .setSetting(SETTING_IS_PAUSED, formatSettingFlag(true))
                }
                controller?.stop()
                orch.clearControllerReady()
                return START_STICKY
            }
            ServiceNotificationActions.ACTION_SYNC_NOW -> {
                if (orch.onNetworkRegained() || orch.controllerOwner == ControllerOwner.SERVICE) {
                    controller?.start()
                    controller?.status()
                }
                return START_STICKY
            }
        }
        if (intent == null && orch.wantedRunning) {
            orch.onStickyRestart()
        }
        enterForegroundOrFail()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        val orch = ClipboardSyncRuntime.orchestrator
        val wanted = orch.wantedRunning
        stopOwnedController()
        if (wanted && orch.processState != ServiceProcessState.ERROR) {
            orch.onProcessKilled()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForegroundOrFail() {
        val orch = ClipboardSyncRuntime.orchestrator
        orch.notificationsVisible = NotificationPermission(this).isGranted()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            orch.onForegroundStarted()
            ensureController()
            registerNetworkCallback()
        } catch (error: Exception) {
            orch.onForegroundStartFailed(error)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    private fun ensureController() {
        if (controller != null) {
            ClipboardSyncRuntime.orchestrator.onServiceControllerStarted()
            controller?.start()
            return
        }
        val pairingStore = ClipServices.pairingStore(this)
        val repository = ClipServices.repository(this)
        val writeCoordinator = ClipServices.writeCoordinator(this)
        val notifier = InboundClipNotifier(this)
        val applier = InboundClipApplier(repository, writeCoordinator) { eventId ->
            notifier.notifyCopyAction(eventId)
        }
        val created = createSyncController(
            pairingStore = pairingStore,
            repository = repository,
            scope = scope,
            onRemoteClipsCommitted = { clips ->
                scope.launch(Dispatchers.IO) {
                    applier.onCommitted(
                        clips.map { InboundClip(eventId = it.eventId, content = it.content) },
                    )
                }
            },
        )
        controller = created
        ClipboardSyncRuntime.attachServiceController(created)
        ClipboardSyncRuntime.orchestrator.onServiceControllerStarted()
        created.start()
    }

    private fun stopOwnedController() {
        controller?.stop()
        controller = null
        ClipboardSyncRuntime.detachServiceController()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (ClipboardSyncRuntime.orchestrator.onNetworkRegained()) {
                    controller?.start()
                    controller?.status()
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null) {
            ensureChannel(manager)
        }
        val spec = ClipboardSyncRuntime.orchestrator.buildNotificationSpec()
        val app = applicationContext
        val builder = NotificationCompat.Builder(app, spec.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_notify_title))
            .setContentText(getString(R.string.service_notify_text))
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openStatusIntent(app))
        spec.actions.forEach { action ->
            val pending = pendingFor(app, action)
            builder.addAction(R.drawable.ic_notification, actionTitle(action.id), pending)
        }
        return builder.build()
    }

    private fun actionTitle(id: String): String = when (id) {
        ServiceNotificationActions.PAUSE_ALL -> getString(R.string.service_action_pause)
        ServiceNotificationActions.SYNC_NOW -> getString(R.string.service_action_sync_now)
        else -> getString(R.string.service_action_open_status)
    }

    private fun pendingFor(app: Context, action: ServiceNotificationAction): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action.componentClass == ServiceNotificationActions.COMPONENT_ACTIVITY) {
            openStatusIntent(app)
        } else {
            PendingIntent.getService(
                app,
                action.id.hashCode(),
                Intent(app, ClipboardSyncService::class.java).setAction(action.intentAction),
                flags,
            )
        }
    }

    private fun openStatusIntent(app: Context): PendingIntent {
        val launch = Intent(app, MainActivity::class.java).apply {
            action = ServiceNotificationActions.ACTION_OPEN_STATUS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ServiceNotificationActions.EXTRA_OPEN_TAB, ServiceNotificationActions.TAB_STATUS)
        }
        return PendingIntent.getActivity(
            app,
            OPEN_STATUS_REQUEST,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(ServiceNotificationActions.CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                ServiceNotificationActions.CHANNEL_ID,
                getString(R.string.service_notify_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_notify_channel_description)
            },
        )
    }

    companion object {
        const val NOTIFICATION_ID = 0x51C5
        private const val OPEN_STATUS_REQUEST = 0x51C6

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ClipboardSyncService::class.java)
                .setAction(ServiceNotificationActions.ACTION_START)
            ContextCompat.startForegroundService(app, intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ClipboardSyncService::class.java)
                .setAction(ServiceNotificationActions.ACTION_STOP)
            runCatching { app.startService(intent) }
            runCatching { app.stopService(Intent(app, ClipboardSyncService::class.java)) }
        }
    }
}
