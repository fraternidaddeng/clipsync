package com.clipsync.android

import android.app.Application
import com.clipsync.android.storage.RETENTION_PURGE_INTERVAL_MS
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
