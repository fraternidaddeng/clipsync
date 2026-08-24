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
        val store = com.clipsync.android.platform.SharedPrefsKeyValueStore(context, name = "inbox-delivery-test")
        inbox = KeyValueClipInbox(store)
        SyncServices.install(
            outbox = KeyValueClipOutbox(store),
            inbox = inbox,
            syncRequester = {},
        )
    }

    @After
    fun tearDown() {
        InboxDelivery.writerFactory = InboxDelivery.defaultWriterFactory
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
        val settings = SyncSettingsStore(
            com.clipsync.android.platform.SharedPrefsKeyValueStore(context, name = "inbox-delivery-settings"),
        )
        assertTrue(InboxDelivery.autoApplyAllowed(settings))

        settings.syncPaused = true
        assertFalse(InboxDelivery.autoApplyAllowed(settings))

        settings.syncPaused = false
        settings.autoApplyRemote = false
        assertFalse(InboxDelivery.autoApplyAllowed(settings))
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
        return manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }

    private class FailingWriter : ClipboardWriter {
        override fun probe(): CapabilityState = CapabilityState.READY

        override fun writeText(text: String, originEventId: String): ClipboardWriteResult =
            ClipboardWriteResult.Failure("CLIPBOARD_WRITE_DENIED")
    }

    private class RecordingWriter : ClipboardWriter {
        var lastOriginEventId: String? = null

        override fun probe(): CapabilityState = CapabilityState.READY

        override fun writeText(text: String, originEventId: String): ClipboardWriteResult {
            lastOriginEventId = originEventId
            return ClipboardWriteResult.Success
        }
    }
}
