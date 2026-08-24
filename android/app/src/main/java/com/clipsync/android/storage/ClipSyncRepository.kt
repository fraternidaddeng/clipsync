package com.clipsync.android.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Cap on rows the history list ever holds in memory at once. */
const val MAX_HISTORY_ROWS = 500

/** A visible clip as the UI consumes it; deleted/expired rows never surface. */
data class ClipEntry(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
)

/**
 * Read path for the 一屏 history list. Kept intentionally small: the Stage 4
 * sync engine owns capture/receive and writes through its own fuller
 * repository into the same table.
 */
interface ClipSyncRepository {
    /** Visible clips, newest first, filtered by [query] (blank = all). */
    fun observeSearch(query: String): Flow<List<ClipEntry>>

    /** The entry when it is still visible (not deleted, not expired). */
    suspend fun findVisible(eventId: String): ClipEntry?

    /**
     * Local delete only: blanks this device's copy and keeps a tombstone.
     * Content already delivered to other devices is not recalled.
     */
    suspend fun delete(eventId: String, nowMs: Long)
}

/** SQL LIKE helpers shared by the DAO query and its tests. */
object ClipSearch {
    /**
     * Contains-style pattern with `\`-escaped wildcards, or null when the
     * query is blank and everything should match.
     */
    fun likePattern(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val escaped = trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}

class RoomClipSyncRepository(private val dao: ClipDao) : ClipSyncRepository {
    override fun observeSearch(query: String): Flow<List<ClipEntry>> {
        val pattern = ClipSearch.likePattern(query)
        return dao
            .observeSearchVisible(
                matchAll = if (pattern == null) 1 else 0,
                pattern = pattern ?: "%",
                limit = MAX_HISTORY_ROWS,
            )
            .map { entities -> entities.mapNotNull { it.toVisibleEntry() } }
    }

    override suspend fun findVisible(eventId: String): ClipEntry? {
        val entity = dao.findByEventId(eventId) ?: return null
        if (entity.deletedAt != null) {
            return null
        }
        return entity.toVisibleEntry()
    }

    override suspend fun delete(eventId: String, nowMs: Long) {
        dao.softDelete(eventId, nowMs)
    }
}

private fun ClipEntity.toVisibleEntry(): ClipEntry? {
    val text = content ?: return null
    return ClipEntry(
        eventId = eventId,
        originDeviceId = originDeviceId,
        originSeq = originSeq,
        content = text,
        contentHash = contentHash,
        sourceApp = sourceApp,
        createdAtMs = createdAt,
        expiresAtMs = expiresAt,
    )
}
