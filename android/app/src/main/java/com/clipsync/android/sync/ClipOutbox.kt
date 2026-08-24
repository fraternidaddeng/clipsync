package com.clipsync.android.sync

import com.clipsync.android.pairing.KeyValueStore
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where a locally created clip event entered the app (plan 3.4: 来源记录). */
enum class ClipSource {
    SHARE_SHEET,
    QUICK_TILE,
    FOREGROUND_APP,
}

/**
 * One locally created text event waiting to be uploaded to the paired Windows peer.
 * `eventId` follows the shared protocol rule (UUID); `origin_seq` is assigned later by the
 * Room-backed sequence store when the sync engine picks the entry up.
 */
@Serializable
data class OutboxEntry(
    val eventId: String,
    val text: String,
    val contentHash: String,
    val utf8Bytes: Int,
    val source: ClipSource,
    val createdAtEpochMillis: Long,
)

sealed interface EnqueueResult {
    data class Accepted(val entry: OutboxEntry) : EnqueueResult

    /** Empty text is never queued (plan 4 阶段: 对空内容显示明确状态). */
    data object EmptyText : EnqueueResult

    /** Same content hash within the 2 s dedup window (plan 3.3 rule 2). */
    data object DuplicateRecent : EnqueueResult

    /** Above the 1 MiB UTF-8 limit; never truncated silently (plan 3.3 rule 9). */
    data object TooLarge : EnqueueResult
}

/**
 * Local outbox port for the Android -> Windows direction. System entry points (share target,
 * Quick Settings tile) only talk to this interface; the Room-backed implementation replaces
 * [KeyValueClipOutbox] when Stage-4 storage lands, without touching the entry points.
 */
interface ClipOutbox {
    fun enqueue(text: String, source: ClipSource): EnqueueResult

    /** Pending entries in enqueue order, for the sync engine and for the UI queue count. */
    fun pending(): List<OutboxEntry>

    /** Removes an entry once the peer has acked it (or the user cancelled it). */
    fun remove(eventId: String)
}

/**
 * Placeholder persistence: entries survive process death via the shared [KeyValueStore]
 * (SharedPreferences on device) as one JSON document. It enforces the same size/dedup rules
 * the Room implementation must keep, so entry-point behaviour will not change later.
 */
class KeyValueClipOutbox(
    private val store: KeyValueStore,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
    private val maxUtf8Bytes: Int = MAX_UTF8_BYTES,
    private val dedupWindowMillis: Long = DEDUP_WINDOW_MILLIS,
) : ClipOutbox {

    override fun enqueue(text: String, source: ClipSource): EnqueueResult {
        if (text.isEmpty()) {
            return EnqueueResult.EmptyText
        }
        val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8).size
        if (utf8Bytes > maxUtf8Bytes) {
            return EnqueueResult.TooLarge
        }
        val contentHash = hasher.hash(text)
        val now = nowEpochMillis()
        val entries = load()
        val last = entries.lastOrNull()
        if (last != null &&
            last.contentHash == contentHash &&
            now - last.createdAtEpochMillis <= dedupWindowMillis
        ) {
            return EnqueueResult.DuplicateRecent
        }
        val entry = OutboxEntry(
            eventId = newEventId(),
            text = text,
            contentHash = contentHash,
            utf8Bytes = utf8Bytes,
            source = source,
            createdAtEpochMillis = now,
        )
        save(entries + entry)
        return EnqueueResult.Accepted(entry)
    }

    override fun pending(): List<OutboxEntry> = load()

    override fun remove(eventId: String) {
        val entries = load()
        val remaining = entries.filterNot { it.eventId == eventId }
        if (remaining.size != entries.size) {
            save(remaining)
        }
    }

    private fun load(): List<OutboxEntry> {
        val raw = store.read(STORAGE_KEY) ?: return emptyList()
        return try {
            json.decodeFromString<List<OutboxEntry>>(raw)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun save(entries: List<OutboxEntry>) {
        store.write(mapOf(STORAGE_KEY to json.encodeToString(entries)))
    }

    companion object {
        const val MAX_UTF8_BYTES: Int = 1 shl 20
        const val DEDUP_WINDOW_MILLIS: Long = 2_000L
        private const val STORAGE_KEY = "outbox.pending"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
