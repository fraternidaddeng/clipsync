package com.clipsync.android.storage

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipEventDao {
    @Insert
    suspend fun insert(event: ClipEventEntity)

    @Query(
        """
        SELECT * FROM clips
        WHERE event_id = :eventId AND (:includeDeleted OR deleted_at IS NULL)
        LIMIT 1
        """,
    )
    suspend fun getByEventId(eventId: String, includeDeleted: Boolean): ClipEventEntity?

    @Query("SELECT * FROM clips WHERE origin_device_id = :originDeviceId AND origin_seq = :originSeq LIMIT 1")
    suspend fun getByOriginSeq(originDeviceId: String, originSeq: Long): ClipEventEntity?

    @Query(
        """
        SELECT * FROM clips
        WHERE deleted_at IS NULL
          AND (:pattern IS NULL OR content LIKE :pattern ESCAPE '\')
        ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun search(pattern: String?, limit: Int, offset: Int): List<ClipEventEntity>

    /** Reactive [search] without paging, for the history screen. */
    @Query(
        """
        SELECT * FROM clips
        WHERE deleted_at IS NULL
          AND (:pattern IS NULL OR content LIKE :pattern ESCAPE '\')
        ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
        LIMIT :limit
        """,
    )
    fun observeSearch(pattern: String?, limit: Int): Flow<List<ClipEventEntity>>

    @Query(
        """
        UPDATE clips
        SET content = '', content_hash = '', source_app = NULL,
            deleted_at = :deletedAtMs, terminal_reason = 'deleted'
        WHERE deleted_at IS NULL AND event_id = :eventId
        """,
    )
    suspend fun softDelete(eventId: String, deletedAtMs: Long): Int

    @Query(
        """
        UPDATE clips
        SET content = '', content_hash = '', source_app = NULL,
            deleted_at = :deletedAtMs, terminal_reason = 'deleted'
        WHERE deleted_at IS NULL
        """,
    )
    suspend fun softDeleteAll(deletedAtMs: Long): Int

    @Query(
        """
        WITH cleanup_candidates AS (
            SELECT event_id FROM clips
            WHERE deleted_at IS NULL AND created_at < :oldestCreatedAtMs
            UNION
            SELECT event_id FROM (
                SELECT event_id FROM clips
                WHERE deleted_at IS NULL
                ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
                LIMIT -1 OFFSET :maximumEntries
            )
        )
        UPDATE clips
        SET content = '', content_hash = '', source_app = NULL,
            deleted_at = :deletedAtMs, terminal_reason = 'expired'
        WHERE deleted_at IS NULL AND event_id IN (SELECT event_id FROM cleanup_candidates)
        """,
    )
    suspend fun cleanup(oldestCreatedAtMs: Long, maximumEntries: Int, deletedAtMs: Long): Int

    @Query(
        """
        UPDATE clips SET applied_at = :appliedAtMs
        WHERE event_id = :eventId AND deleted_at IS NULL
        """,
    )
    suspend fun markApplied(eventId: String, appliedAtMs: Long): Int

    @Query(
        """
        SELECT * FROM clips
        WHERE origin_device_id = :originDeviceId
          AND origin_seq >= :startSeq AND origin_seq <= :endSeq
        ORDER BY origin_seq
        LIMIT :limit
        """,
    )
    suspend fun syncableInRange(
        originDeviceId: String,
        startSeq: Long,
        endSeq: Long,
        limit: Int,
    ): List<ClipEventEntity>

    @Query(
        """
        SELECT * FROM clips
        WHERE event_id IN (:eventIds)
        ORDER BY origin_device_id, origin_seq
        """,
    )
    suspend fun syncableByIds(eventIds: List<String>): List<ClipEventEntity>

    @Query(
        """
        SELECT content FROM clips
        WHERE content_hash = :contentHash AND deleted_at IS NULL AND kind = 'text'
        LIMIT 1
        """,
    )
    suspend fun findLiveContentByHash(contentHash: String): String?

    @Query(
        """
        SELECT COUNT(*) FROM clips
        WHERE content_hash = :contentHash AND deleted_at IS NULL AND kind = 'image'
        """,
    )
    suspend fun countLiveImagesByHash(contentHash: String): Int

    /**
     * Marks live local images that a text-only session announced as `local_only` (ADR 0005 §4).
     * First mark wins so the badge keeps the original downgrade time across reconnects.
     */
    @Query(
        """
        UPDATE clips SET local_only_at = :markedAtMs
        WHERE event_id IN (:eventIds) AND deleted_at IS NULL
          AND kind = 'image' AND local_only_at IS NULL
        """,
    )
    suspend fun markImagesLocalOnly(eventIds: List<String>, markedAtMs: Long): Int

    /** Clears the mark when a v2 session later announces the image as available again. */
    @Query(
        """
        UPDATE clips SET local_only_at = NULL
        WHERE event_id IN (:eventIds) AND local_only_at IS NOT NULL
        """,
    )
    suspend fun clearImagesLocalOnly(eventIds: List<String>): Int

    @Query("SELECT COUNT(*) FROM clips WHERE deleted_at IS NULL")
    suspend fun countVisible(): Int

    /** Every row — live and terminal — in deterministic order for the history export. */
    @Query("SELECT * FROM clips ORDER BY origin_device_id, origin_seq")
    suspend fun exportAll(): List<ClipEventEntity>
}

/** A pending outbox row joined with the clip it must announce; a terminal clip announces `unavailable`. */
data class OutboxBatchRow(
    val outboxId: Long,
    val peerId: String,
    val state: String,
    val attempts: Int,
    @Embedded val clip: ClipEventEntity,
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<OutboxEntryEntity>)

    @Query(
        """
        SELECT o.id AS outboxId, o.peer_id AS peerId, o.state AS state, o.attempts AS attempts, c.*
        FROM outbox o
        JOIN clips c ON c.event_id = o.event_id
        WHERE o.peer_id = :peerId AND o.state = 'pending'
        ORDER BY o.id
        LIMIT :limit
        """,
    )
    suspend fun pendingBatch(peerId: String, limit: Int): List<OutboxBatchRow>

    @Query("UPDATE outbox SET state = 'announced', attempts = attempts + 1 WHERE id IN (:outboxIds)")
    suspend fun markAnnounced(outboxIds: List<Long>)

    @Query("UPDATE outbox SET state = 'pending' WHERE peer_id = :peerId AND state = 'announced'")
    suspend fun resetToPending(peerId: String)

    @Query(
        """
        DELETE FROM outbox
        WHERE peer_id = :peerId AND origin_device_id = :originDeviceId
          AND origin_seq >= :startSeq AND origin_seq <= :endSeq
        """,
    )
    suspend fun deleteAckedRange(peerId: String, originDeviceId: String, startSeq: Long, endSeq: Long)

    @Query("DELETE FROM outbox WHERE peer_id = :peerId")
    suspend fun deleteForPeer(peerId: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE peer_id = :peerId AND state = 'pending'")
    suspend fun pendingCount(peerId: String): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'pending'")
    suspend fun totalPendingCount(): Int
}

@Dao
interface PeerCursorDao {
    @Query("SELECT * FROM peer_cursors WHERE peer_id = :peerId AND origin_device_id = :originDeviceId")
    suspend fun get(peerId: String, originDeviceId: String): PeerCursorEntity?

    @Query("SELECT * FROM peer_cursors WHERE peer_id = :peerId")
    suspend fun listForPeer(peerId: String): List<PeerCursorEntity>

    @Upsert
    suspend fun upsert(cursor: PeerCursorEntity)

    @Query("DELETE FROM peer_cursors WHERE peer_id = :peerId")
    suspend fun deleteForPeer(peerId: String)
}

@Dao
interface OriginReceiveStateDao {
    @Query("SELECT * FROM origin_receive_state WHERE origin_device_id = :originDeviceId")
    suspend fun get(originDeviceId: String): OriginReceiveStateEntity?

    @Query("SELECT * FROM origin_receive_state")
    suspend fun all(): List<OriginReceiveStateEntity>

    @Upsert
    suspend fun upsert(state: OriginReceiveStateEntity)
}

@Dao
interface LocalSequenceDao {
    @Query("SELECT next_seq FROM local_sequences WHERE device_id = :deviceId")
    suspend fun nextSeq(deviceId: String): Long?

    @Upsert
    suspend fun upsert(sequence: LocalSequenceEntity)
}

@Dao
interface MediaBlobDao {
    @Upsert
    suspend fun upsert(blob: MediaBlobEntity)

    @Query("SELECT * FROM media_blobs WHERE content_hash = :contentHash LIMIT 1")
    suspend fun find(contentHash: String): MediaBlobEntity?

    @Query("SELECT content_hash FROM media_blobs")
    suspend fun allHashes(): List<String>

    @Query("DELETE FROM media_blobs WHERE content_hash NOT IN (SELECT content_hash FROM clip_media)")
    suspend fun deleteUnreferenced(): Int
}

@Dao
interface ClipMediaDao {
    @Upsert
    suspend fun upsert(link: ClipMediaEntity)

    @Query("SELECT * FROM clip_media WHERE event_id = :eventId LIMIT 1")
    suspend fun find(eventId: String): ClipMediaEntity?

    @Query("SELECT DISTINCT content_hash FROM clip_media")
    suspend fun referencedHashes(): List<String>

    @Query("DELETE FROM clip_media WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String)

    /** Drops links whose clip row is gone or terminal, so blob GC can reclaim the bytes. */
    @Query(
        """
        DELETE FROM clip_media
        WHERE event_id NOT IN (SELECT event_id FROM clips)
           OR event_id IN (SELECT event_id FROM clips WHERE deleted_at IS NOT NULL)
        """,
    )
    suspend fun deleteOrphaned(): Int
}
