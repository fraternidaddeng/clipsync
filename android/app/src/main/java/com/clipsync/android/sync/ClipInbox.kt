package com.clipsync.android.sync

import com.clipsync.android.pairing.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One text event received from the paired Windows peer, kept for notification copy actions. */
@Serializable
data class InboxItem(
    val eventId: String,
    val text: String,
    val receivedAtEpochMillis: Long,
)

/**
 * Inbox port for the Windows -> Android direction. The copy-action notification never carries
 * the clipboard text itself; the broadcast receiver resolves the event id through this
 * interface instead, so notification listeners of other apps cannot read the content.
 * The Room-backed inbox replaces [KeyValueClipInbox] when Stage-4 storage lands.
 */
interface ClipInbox {
    fun record(eventId: String, text: String, receivedAtEpochMillis: Long)

    fun textFor(eventId: String): String?
}

/** Placeholder persistence over the shared [KeyValueStore]; keeps only the most recent items. */
class KeyValueClipInbox(
    private val store: KeyValueStore,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
) : ClipInbox {

    override fun record(eventId: String, text: String, receivedAtEpochMillis: Long) {
        val items = load().filterNot { it.eventId == eventId } +
            InboxItem(eventId, text, receivedAtEpochMillis)
        save(items.takeLast(maxItems))
    }

    override fun textFor(eventId: String): String? =
        load().firstOrNull { it.eventId == eventId }?.text

    private fun load(): List<InboxItem> {
        val raw = store.read(STORAGE_KEY) ?: return emptyList()
        return try {
            json.decodeFromString<List<InboxItem>>(raw)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun save(items: List<InboxItem>) {
        store.write(mapOf(STORAGE_KEY to json.encodeToString(items)))
    }

    companion object {
        const val DEFAULT_MAX_ITEMS: Int = 50
        private const val STORAGE_KEY = "inbox.recent"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
