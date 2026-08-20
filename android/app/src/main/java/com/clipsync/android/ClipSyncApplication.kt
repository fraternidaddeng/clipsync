package com.clipsync.android

import android.app.Application
import com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedHostStarter
import com.clipsync.android.storage.RETENTION_PURGE_INTERVAL_MS
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.storage.isRetentionPurgeDue
import com.clipsync.android.ui.settings.ClipServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Named application class. MIUI 14's LoadedApk.makeApplicationInner NPEs when
 * Shizuku starts a UserService process and [ApplicationInfo.className] is null.
 */
class ClipSyncApplication : Application() {
    private val retentionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The Shizuku UserService host (":clipsync-clipboard") also instantiates
        // this Application; Room and capture must only run in the main process.
        if (getProcessName() != packageName) {
            return
        }
        if (PrivilegedHostStarter.writeScript(this) == null) {
            retentionScope.launch {
                repeat(6) {
                    delay(5_000)
                    if (PrivilegedHostStarter.writeScript(this@ClipSyncApplication) != null) {
                        return@launch
                    }
                }
            }
        }
        retentionScope.launch {
            // PairingStore (SharedPreferences) is the source of truth for peer
            // identity; reconcile the Room mirror once per process start so a
            // crash between pairing and the UI-driven sync cannot leave it stale.
            runCatching {
                // Open Room FIRST: reading the peer before the (slow) DB init
                // could sample a pre-pairing null and then overwrite an id that
                // an auto-confirm pairing wrote while Room was initializing.
                val repository = ClipServices.repository(this@ClipSyncApplication)
                val peerId = ClipServices.pairingStore(this@ClipSyncApplication)
                    .peer()?.deviceId.orEmpty()
                repository.setSetting(SETTING_PAIRED_PEER_ID, peerId)
            }
        }
        retentionScope.launch {
            var lastRunMs: Long? = null
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                if (isRetentionPurgeDue(lastRunMs, nowMs)) {
                    // Never log clip bodies or exception text; Room errors can echo SQL binds.
                    runCatching {
                        ClipServices.repository(this@ClipSyncApplication).purgeExpired(nowMs)
                    }
                    lastRunMs = nowMs
                }
                delay(RETENTION_PURGE_INTERVAL_MS)
            }
        }
    }
}
