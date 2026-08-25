package com.clipsync.android.sync

import com.clipsync.android.pairing.FakeKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyValueClipOutboxTest {

    private val store = FakeKeyValueStore()
    private var nowMillis = 1_000L
    private var nextId = 0

    private fun outbox(maxUtf8Bytes: Int = KeyValueClipOutbox.MAX_UTF8_BYTES) = KeyValueClipOutbox(
        store = store,
        nowEpochMillis = { nowMillis },
        newEventId = { "event-${nextId++}" },
        maxUtf8Bytes = { maxUtf8Bytes },
    )

    @Test
    fun `accepted entry is persisted with hash, byte count and source`() {
        val result = outbox().enqueue("你好 windows", ClipSource.SHARE_SHEET)

        val accepted = result as EnqueueResult.Accepted
        assertEquals("event-0", accepted.entry.eventId)
        assertEquals("你好 windows", accepted.entry.text)
        assertEquals("你好 windows".toByteArray(Charsets.UTF_8).size, accepted.entry.utf8Bytes)
        assertEquals(64, accepted.entry.contentHash.length)
        assertEquals(ClipSource.SHARE_SHEET, accepted.entry.source)
        assertEquals(1_000L, accepted.entry.createdAtEpochMillis)
    }

    @Test
    fun `entries survive a new outbox instance over the same store`() {
        outbox().enqueue("first", ClipSource.QUICK_TILE)

        val reloaded = outbox().pending()

        assertEquals(1, reloaded.size)
        assertEquals("first", reloaded[0].text)
        assertEquals(ClipSource.QUICK_TILE, reloaded[0].source)
    }

    @Test
    fun `empty text is rejected`() {
        assertEquals(EnqueueResult.EmptyText, outbox().enqueue("", ClipSource.SHARE_SHEET))
        assertTrue(outbox().pending().isEmpty())
    }

    @Test
    fun `text above the byte limit is rejected without truncation`() {
        val outbox = outbox(maxUtf8Bytes = 8)

        assertEquals(EnqueueResult.TooLarge, outbox.enqueue("123456789", ClipSource.SHARE_SHEET))
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun `byte limit counts utf8 bytes not characters`() {
        val outbox = outbox(maxUtf8Bytes = 8)

        // Three CJK chars = 9 UTF-8 bytes even though it is only 3 characters.
        assertEquals(EnqueueResult.TooLarge, outbox.enqueue("你好吗", ClipSource.SHARE_SHEET))
    }

    @Test
    fun `text exactly at the byte limit is accepted`() {
        val outbox = outbox(maxUtf8Bytes = 8)

        assertTrue(outbox.enqueue("12345678", ClipSource.SHARE_SHEET) is EnqueueResult.Accepted)
    }

    @Test
    fun `a changed user cap applies to the next enqueue without a new outbox`() {
        var cap = 4
        val outbox = KeyValueClipOutbox(
            store = store,
            nowEpochMillis = { nowMillis },
            newEventId = { "event-${nextId++}" },
            maxUtf8Bytes = { cap },
        )

        assertEquals(EnqueueResult.TooLarge, outbox.enqueue("12345", ClipSource.SHARE_SHEET))
        cap = 8
        assertTrue(outbox.enqueue("12345", ClipSource.SHARE_SHEET) is EnqueueResult.Accepted)
    }

    @Test
    fun `the user cap can never raise the limit past the protocol 1 MiB`() {
        val outbox = outbox(maxUtf8Bytes = Int.MAX_VALUE)
        val oversized = "a".repeat(KeyValueClipOutbox.MAX_UTF8_BYTES + 1)

        assertEquals(EnqueueResult.TooLarge, outbox.enqueue(oversized, ClipSource.SHARE_SHEET))
    }

    @Test
    fun `same text within two seconds is deduplicated`() {
        val outbox = outbox()
        outbox.enqueue("dup", ClipSource.SHARE_SHEET)
        nowMillis += 2_000L

        assertEquals(EnqueueResult.DuplicateRecent, outbox.enqueue("dup", ClipSource.QUICK_TILE))
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `same text after the window is accepted again`() {
        val outbox = outbox()
        outbox.enqueue("dup", ClipSource.SHARE_SHEET)
        nowMillis += 2_001L

        assertTrue(outbox.enqueue("dup", ClipSource.SHARE_SHEET) is EnqueueResult.Accepted)
        assertEquals(2, outbox.pending().size)
    }

    @Test
    fun `different text within the window is accepted`() {
        val outbox = outbox()
        outbox.enqueue("one", ClipSource.SHARE_SHEET)

        assertTrue(outbox.enqueue("two", ClipSource.SHARE_SHEET) is EnqueueResult.Accepted)
        assertEquals(listOf("one", "two"), outbox.pending().map { it.text })
    }

    @Test
    fun `remove drops only the acked entry`() {
        val outbox = outbox()
        val first = outbox.enqueue("one", ClipSource.SHARE_SHEET) as EnqueueResult.Accepted
        outbox.enqueue("two", ClipSource.SHARE_SHEET)

        outbox.remove(first.entry.eventId)

        assertEquals(listOf("two"), outbox.pending().map { it.text })
    }

    @Test
    fun `corrupt persisted payload degrades to an empty queue instead of crashing`() {
        store.write(mapOf("outbox.pending" to "{not json"))

        assertTrue(outbox().pending().isEmpty())
        assertTrue(outbox().enqueue("recovered", ClipSource.SHARE_SHEET) is EnqueueResult.Accepted)
    }
}
