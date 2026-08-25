package com.clipsync.android.sync

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Foreground-service lifecycle smoke test on a real Android system server: the production
 * [ClipboardSyncService.start] entry point must promote the service to the foreground with the
 * `connectedDevice` type (manifest prerequisites included), and [ClipboardSyncService.stop]
 * must tear it down and reset every conduit-facing state flow. The whole real stack launches —
 * Keystore-backed pairing store, Room repository, supervisor with no pairing — which is exactly
 * the surface Robolectric cannot vouch for.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardSyncServiceSmokeTest {
    @Test
    fun startPromotesToForegroundAndStopTearsDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        ClipboardSyncService.start(context)
        try {
            awaitUntil("service reports running") { ClipboardSyncService.serviceRunning.value }
            // The system either grants the foreground promotion (RunningServiceInfo.foreground)
            // or the service degrades via START_ERROR_FGS_DENIED and stops itself; this smoke
            // test requires the granted path.
            awaitUntil("service promoted to foreground") {
                runningServiceInfo(context)?.foreground == true
            }
            assertNull(
                "foreground start must not report a start error",
                ClipboardSyncService.startErrorCodes.value,
            )
        } finally {
            ClipboardSyncService.stop(context)
        }

        awaitUntil("serviceRunning flow resets") { !ClipboardSyncService.serviceRunning.value }
        awaitUntil("service leaves the running-services list") { runningServiceInfo(context) == null }
        assertEquals(SyncConnectionState.NotPaired, ClipboardSyncService.connectionStates.value)
    }

    /** Own-app query of the running-services list; still supported for the caller's services. */
    private fun runningServiceInfo(context: Context): ActivityManager.RunningServiceInfo? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == ClipboardSyncService::class.java.name }
    }

    // Generous ceiling so the test also passes on slow (e.g. software-emulated) devices;
    // on real hardware every wait resolves in well under a second.
    private fun awaitUntil(
        what: String,
        timeoutMs: Long = 60_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out after ${timeoutMs}ms waiting for: $what")
    }
}
