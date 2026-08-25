package com.clipsync.android.platform.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.R
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
 * The charter's whole notification surface is: the polyline small icon, flow blue
 * (#215F8F) via setColor, fixed Chinese copy from strings.xml, and one channel group.
 * No notification ever carries clipboard text (threat model: lock screen).
 */
@RunWith(RobolectricTestRunner::class)
class SyncNotificationsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun `ensureChannels groups sync, inbox and recovery under one 剪贴同步 group`() {
        SyncNotifications.ensureChannels(context)

        val group = manager.notificationChannelGroups.single()
        assertEquals(SyncNotifications.GROUP_ID, group.id)
        assertEquals(
            context.getString(R.string.notification_group_name),
            group.name.toString(),
        )

        listOf(
            SyncNotifications.CHANNEL_SYNC,
            SyncNotifications.CHANNEL_INBOX,
            SyncNotifications.CHANNEL_RECOVERY,
        ).forEach { id ->
            val channel = manager.getNotificationChannel(id)
            assertEquals(SyncNotifications.GROUP_ID, channel.group)
            assertFalse(channel.description.isNullOrBlank())
        }

        // The resident state channel stays quiet and never badges the launcher.
        val sync = manager.getNotificationChannel(SyncNotifications.CHANNEL_SYNC)
        assertEquals(NotificationManager.IMPORTANCE_LOW, sync.importance)
        assertFalse(sync.canShowBadge())
    }

    @Test
    fun `inbox notification wears the polyline icon, flow blue and only fixed copy`() {
        val notification = SyncNotifications.buildInboxItemNotification(context, "evt-42")

        assertCharterAccents(notification)
        assertEquals(NotificationCompat.CATEGORY_STATUS, notification.category)
        assertEquals(
            context.getString(R.string.notification_inbox_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.notification_inbox_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(
            listOf(context.getString(R.string.notification_action_copy)),
            notification.actions.map { it.title.toString() },
        )
    }

    @Test
    fun `auto-applied status keeps the accents and stays content-free`() {
        assertTrue(SyncNotifications.notifyAutoApplied(context, "evt-7"))

        val notification = shadowOf(manager)
            .getNotification(null, SyncNotifications.notificationIdFor("evt-7"))
        assertCharterAccents(notification)
        assertEquals(
            context.getString(R.string.notification_applied_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        // The fixed title is the only other line; no clipboard body can appear.
        assertEquals(
            context.getString(R.string.notification_inbox_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    }

    @Test
    fun `recovery and auth-throttle notifications share the same charter accents`() {
        assertTrue(SyncNotifications.notifyRecoveryNeeded(context))
        assertTrue(SyncNotifications.notifyAuthThrottled(context))

        val shadow = shadowOf(manager)
        val recovery = shadow.getNotification(null, SyncNotifications.RECOVERY_NOTIFICATION_ID)
        val throttled = shadow.getNotification(null, SyncNotifications.AUTH_THROTTLE_NOTIFICATION_ID)

        listOf(recovery, throttled).forEach(::assertCharterAccents)
        assertEquals(
            context.getString(R.string.notification_recovery_title),
            recovery.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.notification_auth_throttled_title),
            throttled.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }

    private fun assertCharterAccents(notification: Notification) {
        assertEquals(R.drawable.ic_notify_clip, notification.smallIcon.resId)
        assertEquals(FLOW_BLUE, notification.color)
    }

    private companion object {
        /** Charter flow blue (docs/design/tokens.md): the one notification accent. */
        const val FLOW_BLUE = 0xFF215F8F.toInt()
    }
}
