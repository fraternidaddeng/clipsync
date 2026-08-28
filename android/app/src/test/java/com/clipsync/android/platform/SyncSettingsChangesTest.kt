package com.clipsync.android.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SyncSettingsChanges.changes] is the glue that lets long-lived mirrors
 * (PreferencesViewModel, the conduit's master-switch flow) re-read the settings
 * file when another surface — a notification action, the pairing ritual — writes
 * it. The listener must fire per changed write and stop with the collection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncSettingsChangesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val settings =
        SyncSettingsStore(
            SharedPrefsKeyValueStore(context, name = SyncSettingsStore.PREFERENCES_NAME),
        )

    @Test
    fun `writes to the settings file tick the flow while collected, then stop`() =
        runTest(UnconfinedTestDispatcher()) {
            var ticks = 0
            val collection = launch { SyncSettingsChanges.changes(context).collect { ticks++ } }
            assertEquals(0, ticks)

            // The same write path the resident notification's 暂停捕获 action uses.
            settings.autoCapturePaused = true
            assertEquals(1, ticks)

            settings.serviceEnabled = false
            assertEquals(2, ticks)

            // Cancelling the collection unregisters the listener: no further ticks.
            collection.cancel()
            settings.autoCapturePaused = false
            assertEquals(2, ticks)
        }
}
