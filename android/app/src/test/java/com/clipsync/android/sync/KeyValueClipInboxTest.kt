package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyValueClipInboxTest {

    private val store = FakeKeyValueStore()

    @Test
    fun `recorded text is found by event id after a reload`() {
        KeyValueClipInbox(store).record("event-1", "来自电脑的文本", 42L)

        assertEquals("来自电脑的文本", KeyValueClipInbox(store).textFor("event-1"))
    }

    @Test
    fun `unknown event id resolves to null`() {
        assertNull(KeyValueClipInbox(store).textFor("missing"))
    }

    @Test
    fun `re-recording the same event id replaces instead of duplicating`() {
        val inbox = KeyValueClipInbox(store, maxItems = 2)
        inbox.record("event-1", "old", 1L)
        inbox.record("event-2", "other", 2L)
        inbox.record("event-1", "new", 3L)

        assertEquals("new", inbox.textFor("event-1"))
        assertEquals("other", inbox.textFor("event-2"))
    }

    @Test
    fun `oldest items are evicted beyond the cap`() {
        val inbox = KeyValueClipInbox(store, maxItems = 2)
        inbox.record("event-1", "a", 1L)
        inbox.record("event-2", "b", 2L)
        inbox.record("event-3", "c", 3L)

        assertNull(inbox.textFor("event-1"))
        assertEquals("b", inbox.textFor("event-2"))
        assertEquals("c", inbox.textFor("event-3"))
    }

    @Test
    fun `corrupt persisted payload degrades to an empty inbox instead of crashing`() {
        store.write(mapOf("inbox.recent" to "[broken"))

        assertNull(KeyValueClipInbox(store).textFor("event-1"))
    }
}
