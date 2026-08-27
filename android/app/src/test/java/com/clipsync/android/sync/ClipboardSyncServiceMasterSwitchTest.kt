package com.clipsync.android.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The 后台同步服务 master switch (`sync.service_enabled`) is enforced inside
 * [ClipboardSyncService.start] itself, so *every* start path — app open, pairing resume,
 * conduit 启动服务, boot restore, recovery taps — honors it. Off must mean truly off:
 * no entry point may resurrect the service until the user turns the switch back on.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardSyncServiceMasterSwitchTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val settings =
        SyncSettingsStore(
            SharedPrefsKeyValueStore(context, name = SyncSettingsStore.PREFERENCES_NAME),
        )

    @Test
    fun `start refuses while the master switch is off`() {
        settings.serviceEnabled = false

        ClipboardSyncService.start(context)

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun `start proceeds by default and once the switch is back on`() {
        // Default (no stored key): enabled — existing installs keep their service.
        ClipboardSyncService.start(context)
        assertEquals(
            ClipboardSyncService::class.java.name,
            shadowOf(context).nextStartedService?.component?.className,
        )

        settings.serviceEnabled = false
        ClipboardSyncService.start(context)
        assertNull(shadowOf(context).nextStartedService)

        settings.serviceEnabled = true
        ClipboardSyncService.start(context)
        assertEquals(
            ClipboardSyncService::class.java.name,
            shadowOf(context).nextStartedService?.component?.className,
        )
    }
}
