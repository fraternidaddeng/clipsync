package com.clipsync.android.sync

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.clipboard.SharedClipboardWrites
import com.clipsync.android.storage.SyncSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InboxDeliveryTest {
    private lateinit var context: Context
    private lateinit var inbox: ClipInbox

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric recreates the application per test; the shared write coordinator must
        // not keep a writer bound to the previous test's clipboard.
        SharedClipboardWrites.reset()
        val store =
            com.clipsync.android.platform
                .SharedPrefsKeyValueStore(context, name = "inbox-delivery-test")
        inbox = RecordingClipInbox()
        SyncServices.install(
            outbox = KeyValueClipOutbox(store),
            inbox = inbox,
            syncRequester = {},
        )
    }

    /**
     * Observes the record-first contract: production resolves from the Room store (the
     * commit that precedes delivery), this fake just remembers what deliver recorded.
     */
    private class RecordingClipInbox : ClipInbox {
        private val items = mutableMapOf<String, String>()

        override fun record(
            eventId: String,
            text: String,
            receivedAtEpochMillis: Long,
        ) {
            items[eventId] = text
        }

        override fun textFor(eventId: String): String? = items[eventId]
    }

    @After
    fun tearDown() {
        InboxDelivery.writerFactory = InboxDelivery.defaultWriterFactory
        // The gate is JVM-wide state on the InboxDelivery object: a flood test must not
        // leave a spent window behind for the next test's notifications.
        InboxDelivery.notificationGate = InboxNotificationGate()
    }

    @Test
    fun autoApplyOnWritesSystemClipboardAndReportsApplied() {
        val applied = InboxDelivery.deliver(context, "e1", "hello from windows", 123L, autoApply = true)

        assertTrue(applied)
        assertEquals("hello from windows", inbox.textFor("e1"))
        assertEquals("hello from windows", clipboardText())
    }

    @Test
    fun autoApplyOffLeavesClipboardUntouchedButKeepsInboxItem() {
        val applied = InboxDelivery.deliver(context, "e2", "history only", 123L, autoApply = false)

        assertFalse(applied)
        assertEquals("history only", inbox.textFor("e2"))
        assertNull(clipboardText())
    }

    @Test
    fun writeFailureFallsBackToInboxDelivery() {
        InboxDelivery.writerFactory = { FailingWriter() }

        val applied = InboxDelivery.deliver(context, "e3", "oem denied", 123L, autoApply = true)

        assertFalse(applied)
        assertEquals("oem denied", inbox.textFor("e3"))
    }

    @Test
    fun writerReceivesEventIdAsOriginForLoopSuppression() {
        val writer = RecordingWriter()
        InboxDelivery.writerFactory = { writer }

        InboxDelivery.deliver(context, "e4", "suppress me", 123L, autoApply = true)

        assertEquals("e4", writer.lastOriginEventId)
    }

    @Test
    fun autoApplyGateHonoursThePauseSwitch() {
        val settings =
            SyncSettingsStore(
                com.clipsync.android.platform
                    .SharedPrefsKeyValueStore(context, name = "inbox-delivery-settings"),
            )
        assertTrue(InboxDelivery.autoApplyAllowed(settings))

        settings.syncPaused = true
        assertFalse(InboxDelivery.autoApplyAllowed(settings))

        settings.syncPaused = false
        settings.autoApplyRemote = false
        assertFalse(InboxDelivery.autoApplyAllowed(settings))
    }

    @Test
    fun imageAutoApplyGateIsItsOwnOptInAndDefaultOff() {
        val settings =
            SyncSettingsStore(
                com.clipsync.android.platform
                    .SharedPrefsKeyValueStore(context, name = "inbox-delivery-image-settings"),
            )
        // ADR 0004: the text auto_apply_remote default (on) must never write pixel bytes.
        assertTrue(InboxDelivery.autoApplyAllowed(settings))
        assertFalse(InboxDelivery.autoApplyImagesAllowed(settings))

        settings.autoApplyImages = true
        assertTrue(InboxDelivery.autoApplyImagesAllowed(settings))

        // Pause stops the image write too, matching Windows.
        settings.syncPaused = true
        assertFalse(InboxDelivery.autoApplyImagesAllowed(settings))

        // The image gate stays independent: text auto-apply off leaves it usable.
        settings.syncPaused = false
        settings.autoApplyRemote = false
        assertTrue(InboxDelivery.autoApplyImagesAllowed(settings))
    }

    @Test
    fun inboxNotifyGateFollowsThePreference() {
        val settings =
            SyncSettingsStore(
                com.clipsync.android.platform
                    .SharedPrefsKeyValueStore(context, name = "inbox-delivery-notify-settings"),
            )
        // settings-roadmap P1-8: on by default, an in-app total switch for the surface.
        assertTrue(InboxDelivery.inboxNotificationsAllowed(settings))

        settings.inboxNotifyEnabled = false
        assertFalse(InboxDelivery.inboxNotificationsAllowed(settings))
    }

    @Test
    fun notifyOffStillRecordsAndAppliesButPostsNoNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val applied = InboxDelivery.deliver(context, "e6", "quiet delivery", 123L, autoApply = true, notify = false)

        // The delivery itself is untouched: history/inbox record and the clipboard write.
        assertTrue(applied)
        assertEquals("quiet delivery", inbox.textFor("e6"))
        assertEquals("quiet delivery", clipboardText())
        // Only the notification surface goes quiet.
        assertEquals(
            0,
            org.robolectric.Shadows
                .shadowOf(manager)
                .allNotifications.size,
        )

        InboxDelivery.deliver(context, "e7", "quiet manual copy", 124L, autoApply = false, notify = false)
        assertEquals(
            0,
            org.robolectric.Shadows
                .shadowOf(manager)
                .allNotifications.size,
        )
    }

    @Test
    fun imageWithoutAutoApplyPostsTheContentFreeArrivalCard() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val applied =
            InboxDelivery.deliverImage(context, "img-1", contentHash = null, mimeType = null, autoApply = false)

        // Plan 5.6 honesty: the arrival never lands silently even though the write is off.
        assertFalse(applied)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        val card =
            shadow.getNotification(
                com.clipsync.android.platform.notify.SyncNotifications
                    .notificationIdFor("img-1"),
            )
        assertEquals(
            context.getString(com.clipsync.android.R.string.notification_inbox_image_title),
            card.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
        )
        // No 复制 action: the text inbox cannot resolve an image; history holds the thumbnail.
        assertTrue(card.actions.isNullOrEmpty())
        // Nothing reached the clipboard.
        assertNull(clipboardText())
    }

    @Test
    fun notifyOffSilencesTheImageArrivalCardToo() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val applied =
            InboxDelivery.deliverImage(
                context,
                "img-2",
                contentHash = null,
                mimeType = null,
                autoApply = false,
                notify = false,
            )

        assertFalse(applied)
        assertEquals(
            0,
            org.robolectric.Shadows
                .shadowOf(manager)
                .allNotifications.size,
        )
    }

    @Test
    fun imageArrivalCardsShareTheTextFloodGate() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        InboxDelivery.notificationGate = InboxNotificationGate(maxPerWindow = 1, windowMillis = 60_000L)

        InboxDelivery.deliver(context, "mix-text", "text body", 123L)
        InboxDelivery.deliverImage(context, "mix-img", contentHash = null, mimeType = null)

        // One surface, one budget: the image card coalesces into the same counting card.
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        assertEquals(2, shadow.allNotifications.size)
        val summary =
            shadow.getNotification(
                com.clipsync.android.platform.notify.SyncNotifications.INBOX_FLOOD_NOTIFICATION_ID,
            )
        assertEquals(1, summary.number)
    }

    @Test
    fun notificationFloodCoalescesIntoOneCountingCard() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        InboxDelivery.notificationGate = InboxNotificationGate(maxPerWindow = 2, windowMillis = 60_000L)

        repeat(5) { InboxDelivery.deliver(context, "flood-$it", "clip $it", 123L + it) }

        // Two per-event cards plus exactly one coalesced summary — not five separate posts.
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        assertEquals(3, shadow.allNotifications.size)
        val summary =
            shadow.getNotification(
                com.clipsync.android.platform.notify.SyncNotifications.INBOX_FLOOD_NOTIFICATION_ID,
            )
        // The counting card carries the suppressed total of the window (3 of 5 coalesced).
        assertEquals(3, summary.number)
        // Every clip is still recorded: the gate shapes announcements, never delivery.
        repeat(5) { assertEquals("clip $it", inbox.textFor("flood-$it")) }
    }

    @Test
    fun defaultWriterRegistersCaptureLoopSuppression() {
        InboxDelivery.deliver(context, "e5", "auto applied body", 123L, autoApply = true)

        // The capture pipeline consults the same shared coordinator: the clip this app just
        // wrote must be recognized as a self-write and not echoed back to the peer.
        assertTrue(SharedClipboardWrites.coordinator(context).shouldSuppressContent("auto applied body"))
    }

    private fun clipboardText(): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    }

    private class FailingWriter : ClipboardWriter {
        override fun probe(): CapabilityState = CapabilityState.READY

        override fun writeText(
            text: String,
            originEventId: String,
        ): ClipboardWriteResult = ClipboardWriteResult.Failure("CLIPBOARD_WRITE_DENIED")
    }

    private class RecordingWriter : ClipboardWriter {
        var lastOriginEventId: String? = null

        override fun probe(): CapabilityState = CapabilityState.READY

        override fun writeText(
            text: String,
            originEventId: String,
        ): ClipboardWriteResult {
            lastOriginEventId = originEventId
            return ClipboardWriteResult.Success
        }
    }
}
