package com.clipsync.android.platform.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.TileService
import com.clipsync.android.R
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
import com.clipsync.android.sync.ClipboardSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "发送剪贴板": one-shot action that launches the transparent
 * [SendClipboardActivity], which reads the clipboard with real window focus and enqueues it.
 * On a locked device the read would fail, so the tile asks for an unlock first.
 *
 * While the QS panel is open the tile mirrors the live sync state ([SendClipboardTileState]):
 * active only when a tap delivers immediately, inactive when the send would queue or be
 * refused (paused/private/disconnected). Both the connection flow and the settings file are
 * watched, so flipping 暂停 from the notification action next to the panel updates the tile.
 */
class SendClipboardTileService : TileService() {
    private var listeningScope: CoroutineScope? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onStartListening() {
        super.onStartListening()
        // StateFlow collection delivers the current value immediately, so this also paints
        // the initial state. Main.immediate: qsTile updates must happen on the main thread.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listeningScope = scope
        scope.launch {
            ClipboardSyncService.connectionStates.collect { refreshTile() }
        }
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshTile() }
        settingsListener = listener
        settingsPreferences().registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        listeningScope = null
        settingsListener?.let { settingsPreferences().unregisterOnSharedPreferenceChangeListener(it) }
        settingsListener = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchSendActivity() }
        } else {
            launchSendActivity()
        }
    }

    private fun refreshTile() {
        val settings =
            SyncSettingsStore(
                SharedPrefsKeyValueStore(applicationContext, name = SyncSettingsStore.PREFERENCES_NAME),
            )
        qsTile?.apply {
            state =
                SendClipboardTileState.of(
                    connectionState = ClipboardSyncService.connectionStates.value,
                    syncPaused = settings.syncPaused,
                    privateMode = settings.privateMode,
                )
            label = getString(R.string.qs_tile_label)
            updateTile()
        }
    }

    private fun settingsPreferences(): SharedPreferences =
        applicationContext.getSharedPreferences(SyncSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Lint's StartActivityAndCollapseDeprecated fires on the legacy branch even though it
    // only runs below API 34, where the PendingIntent overload does not exist yet; the
    // Kotlin @Suppress("DEPRECATION") below silences the compiler but not this lint id.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchSendActivity() {
        val intent =
            Intent(this, SendClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
