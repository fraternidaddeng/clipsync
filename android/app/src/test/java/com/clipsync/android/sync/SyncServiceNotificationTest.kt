package com.clipsync.android.sync

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.MainActivity
import com.clipsync.android.R
import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.platform.notify.SyncNotifications
import com.clipsync.android.storage.SyncSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Plan 5.2: the resident FGS notification carries 暂停全部同步 / 仅暂停自动捕获 / 立即同步
 * as actions and opens the 通路 (故障状态) tab on tap — and never shows clipboard content.
 */
@RunWith(RobolectricTestRunner::class)
class SyncServiceNotificationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun build(
        syncPaused: Boolean = false,
        capturePaused: Boolean = false,
    ): Notification =
        SyncServiceNotification.build(
            context,
            channelId = SyncNotifications.CHANNEL_SYNC,
            stateText = context.getString(R.string.notification_sync_connected),
            syncPaused = syncPaused,
            autoCapturePaused = capturePaused,
        )

    @Test
    fun `wears the charter accents - polyline icon, flow blue, low priority, no timestamp`() {
        val notification = build()

        // The polyline mark is the small icon; flow blue (#215F8F) is the accent the
        // charter assigns to system surfaces (docs/design/tokens.md).
        assertEquals(R.drawable.ic_notify_clip, notification.smallIcon.resId)
        assertEquals(0xFF215F8F.toInt(), notification.color)
        assertEquals(NotificationCompat.CATEGORY_SERVICE, notification.category)
        @Suppress("DEPRECATION")
        assertEquals(NotificationCompat.PRIORITY_LOW, notification.priority)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
        // A resident state line carries no timestamp: it is "now" by definition.
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    }

    @Test
    fun `default state offers pause-all, pause-capture and sync-now`() {
        val notification = build()

        val titles = notification.actions.map { it.title.toString() }
        assertEquals(
            listOf(
                context.getString(R.string.notification_action_pause_all),
                context.getString(R.string.notification_action_pause_capture),
                context.getString(R.string.notification_action_sync_now),
            ),
            titles,
        )

        val actions = notification.actions.map { shadowOf(it.actionIntent).savedIntent.action }
        assertEquals(
            listOf(
                SyncServiceNotification.ACTION_PAUSE_ALL,
                SyncServiceNotification.ACTION_PAUSE_CAPTURE,
                SyncServiceNotification.ACTION_SYNC_NOW,
            ),
            actions,
        )

        // Every action button targets the running sync service.
        notification.actions.forEach { action ->
            assertEquals(
                ClipboardSyncService::class.java.name,
                shadowOf(action.actionIntent).savedIntent.component?.className,
            )
        }
    }

    @Test
    fun `pause buttons flip to resume while their gate is on`() {
        val bothPaused = build(syncPaused = true, capturePaused = true)

        val titles = bothPaused.actions.map { it.title.toString() }
        assertEquals(
            listOf(
                context.getString(R.string.notification_action_resume_all),
                context.getString(R.string.notification_action_resume_capture),
                context.getString(R.string.notification_action_sync_now),
            ),
            titles,
        )

        val actions = bothPaused.actions.map { shadowOf(it.actionIntent).savedIntent.action }
        assertEquals(
            listOf(
                SyncServiceNotification.ACTION_RESUME_ALL,
                SyncServiceNotification.ACTION_RESUME_CAPTURE,
                SyncServiceNotification.ACTION_SYNC_NOW,
            ),
            actions,
        )
    }

    @Test
    fun `content tap opens the conduit (故障状态) tab of the main activity`() {
        val notification = build()

        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals(MainActivity.TAB_CONDUIT, opened.getIntExtra(MainActivity.EXTRA_OPEN_TAB, -1))
    }

    @Test
    fun `status line names the active pause and never any clipboard content`() {
        // Fixed strings only: title is the connection state, text one of the two status
        // lines (or absent). Nothing user-generated can reach this surface.
        val plain = build()
        assertEquals(
            context.getString(R.string.notification_sync_connected),
            plain.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertNull(plain.extras.getCharSequence(Notification.EXTRA_TEXT))

        val paused = build(syncPaused = true)
        assertEquals(
            context.getString(R.string.notification_status_sync_paused),
            paused.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        // 暂停全部 outranks 仅停捕获 in the status line when both are set.
        val both = build(syncPaused = true, capturePaused = true)
        assertEquals(
            context.getString(R.string.notification_status_sync_paused),
            both.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        val captureOnly = build(capturePaused = true)
        assertEquals(
            context.getString(R.string.notification_status_capture_paused),
            captureOnly.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }
}

/** The pure action→settings mapping the service applies on each notification tap. */
class SyncServiceNotificationActionTest {
    private val settings = SyncSettingsStore(FakeKeyValueStore())

    @Test
    fun `pause all sets the global gate and asks for no immediate sync`() {
        assertFalse(SyncServiceNotification.applyAction(SyncServiceNotification.ACTION_PAUSE_ALL, settings))
        assertTrue(settings.syncPaused)
        assertFalse(settings.autoCapturePaused)
    }

    @Test
    fun `resume all clears the gate and asks for an immediate sync`() {
        settings.syncPaused = true

        assertTrue(SyncServiceNotification.applyAction(SyncServiceNotification.ACTION_RESUME_ALL, settings))
        assertFalse(settings.syncPaused)
    }

    @Test
    fun `capture pause only touches the capture gate`() {
        assertFalse(SyncServiceNotification.applyAction(SyncServiceNotification.ACTION_PAUSE_CAPTURE, settings))
        assertTrue(settings.autoCapturePaused)
        assertFalse(settings.syncPaused)

        assertFalse(SyncServiceNotification.applyAction(SyncServiceNotification.ACTION_RESUME_CAPTURE, settings))
        assertFalse(settings.autoCapturePaused)
    }

    @Test
    fun `sync now changes no settings and asks for an immediate sync`() {
        assertTrue(SyncServiceNotification.applyAction(SyncServiceNotification.ACTION_SYNC_NOW, settings))
        assertFalse(settings.syncPaused)
        assertFalse(settings.autoCapturePaused)
    }

    @Test
    fun `unknown or absent actions change nothing`() {
        assertFalse(SyncServiceNotification.applyAction(null, settings))
        assertFalse(SyncServiceNotification.applyAction("com.other.ACTION", settings))
        assertFalse(settings.syncPaused)
        assertFalse(settings.autoCapturePaused)
    }
}
