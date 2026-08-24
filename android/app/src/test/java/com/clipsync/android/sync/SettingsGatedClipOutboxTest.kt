package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGatedClipOutboxTest {
    private val settings = SyncSettingsStore(FakeKeyValueStore())
    private val delegate = KeyValueClipOutbox(FakeKeyValueStore())
    private val outbox = SettingsGatedClipOutbox(delegate, settings)

    @Test
    fun `without switches the delegate accepts as before`() {
        val result = outbox.enqueue("plain", ClipSource.SHARE_SHEET)

        val accepted = result as EnqueueResult.Accepted
        assertEquals("plain", accepted.entry.text)
        assertEquals(ClipSource.SHARE_SHEET, accepted.entry.source)
    }

    @Test
    fun `paused sync blocks every enqueue`() {
        settings.syncPaused = true

        assertEquals(EnqueueResult.SyncPaused, outbox.enqueue("held", ClipSource.QUICK_TILE))
        assertTrue(delegate.pending().isEmpty())
    }

    @Test
    fun `private mode blocks every enqueue`() {
        settings.privateMode = true

        assertEquals(EnqueueResult.PrivateMode, outbox.enqueue("secret", ClipSource.SHARE_SHEET))
        assertTrue(delegate.pending().isEmpty())
    }

    @Test
    fun `private mode outranks the pause switch`() {
        settings.privateMode = true
        settings.syncPaused = true

        assertEquals(EnqueueResult.PrivateMode, outbox.enqueue("both", ClipSource.SHARE_SHEET))
    }

    @Test
    fun `plan gate order - pause and private run before the size rules`() {
        settings.syncPaused = true
        // Empty text would normally be EmptyText; the pause gate answers first.
        assertEquals(EnqueueResult.SyncPaused, outbox.enqueue("", ClipSource.SHARE_SHEET))

        settings.syncPaused = false
        settings.privateMode = true
        assertEquals(EnqueueResult.PrivateMode, outbox.enqueue("", ClipSource.SHARE_SHEET))
    }

    @Test
    fun `toggling the switch off applies to the next enqueue`() {
        settings.syncPaused = true
        assertEquals(EnqueueResult.SyncPaused, outbox.enqueue("later", ClipSource.SHARE_SHEET))

        settings.syncPaused = false
        assertTrue(outbox.enqueue("later", ClipSource.SHARE_SHEET) is EnqueueResult.Accepted)
    }

    @Test
    fun `pending and remove pass through while paused`() {
        val accepted = outbox.enqueue("before pause", ClipSource.SHARE_SHEET) as EnqueueResult.Accepted
        settings.syncPaused = true

        // Pausing hides nothing: the queued entry stays visible and can still be removed.
        assertEquals(listOf("before pause"), outbox.pending().map { it.text })
        outbox.remove(accepted.entry.eventId)
        assertTrue(outbox.pending().isEmpty())
    }
}
