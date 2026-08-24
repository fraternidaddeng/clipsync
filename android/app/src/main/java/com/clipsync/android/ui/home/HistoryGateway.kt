package com.clipsync.android.ui.home

import com.clipsync.android.storage.ClipHistoryEntry
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.HistoryQuery
import kotlinx.coroutines.flow.Flow

/** Rows the 一屏 list ever holds at once; older content stays reachable by search. */
const val HOME_HISTORY_LIMIT = 500

/**
 * The read-and-maintain seam the home screen consumes, narrowed from
 * [ClipSyncRepository] so the ViewModel stays unit-testable without Room.
 */
interface HistoryGateway {
    /** Visible clips, newest first, filtered by [query] (blank = all). */
    fun observeSearch(query: String): Flow<List<ClipHistoryEntry>>

    /** The entry while it is still visible (not deleted). */
    suspend fun findVisible(eventId: String): ClipHistoryEntry?

    /**
     * Local delete only: blanks this device's copy and keeps a tombstone.
     * Content already delivered to other devices is not recalled.
     */
    suspend fun delete(eventId: String, nowMs: Long)
}

class ClipSyncHistoryGateway(
    private val repository: ClipSyncRepository,
) : HistoryGateway {
    override fun observeSearch(query: String): Flow<List<ClipHistoryEntry>> =
        repository.observeHistory(
            HistoryQuery(
                searchText = query.trim().ifEmpty { null },
                limit = HOME_HISTORY_LIMIT,
            ),
        )

    override suspend fun findVisible(eventId: String): ClipHistoryEntry? =
        repository.getById(eventId, includeDeleted = false)

    override suspend fun delete(eventId: String, nowMs: Long) {
        repository.deleteEvent(eventId, nowMs)
    }
}
