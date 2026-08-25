package com.clipsync.android

import android.app.Application
import com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedHostStarter
import com.clipsync.android.platform.notify.SyncNotifications
import com.clipsync.android.sync.SyncServices

/**
 * Initializes the sync service wiring before any entry point (share target, Quick Settings
 * tile, notification action receiver) runs — those can each be the first component created in
 * a fresh process, so none of them may depend on MainActivity having run.
 */
class ClipSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncServices.initialize(this)
        SyncNotifications.ensureChannels(this)
        // Materialize the adb host-start script on external storage so operators can run
        // `adb shell sh …/clipsync_privileged_host.sh` without the official manager APK.
        PrivilegedHostStarter.writeScript(this)
    }
}
