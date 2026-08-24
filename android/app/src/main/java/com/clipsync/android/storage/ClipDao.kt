package com.clipsync.android.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ClipEntity)

    @Query("SELECT * FROM clips WHERE event_id = :eventId LIMIT 1")
    suspend fun findByEventId(eventId: String): ClipEntity?

    /**
     * Visible history, newest first, optionally filtered by a LIKE pattern
     * built with [ClipSearch.likePattern]. Ordering never relies on the wall
     * clock alone (plan.md §3.3 rule 7).
     */
    @Query(
        """
        SELECT * FROM clips
        WHERE deleted_at IS NULL
          AND (expires_at IS NULL OR expires_at > (CAST(strftime('%s', 'now') AS INTEGER) * 1000))
          AND (:matchAll = 1 OR content LIKE :pattern ESCAPE '\')
        ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
        LIMIT :limit
        """,
    )
    fun observeSearchVisible(matchAll: Int, pattern: String, limit: Int): Flow<List<ClipEntity>>

    /**
     * Local delete: blank the content but keep the row as a tombstone so an
     * already-acked event can never be re-imported (plan.md §3.3 rule 6).
     */
    @Query(
        """
        UPDATE clips
        SET content = '', content_hash = '', source_app = NULL,
            deleted_at = :nowMs, terminal_reason = 'deleted'
        WHERE event_id = :eventId AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(eventId: String, nowMs: Long): Int
}
