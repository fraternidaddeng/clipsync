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

    @Query("SELECT * FROM clips WHERE origin_device_id = :originDeviceId AND origin_seq = :originSeq LIMIT 1")
    suspend fun findByOriginSeq(originDeviceId: String, originSeq: Long): ClipEntity?

    @Query("SELECT * FROM clips WHERE event_id = :eventId LIMIT 1")
    suspend fun findByEventId(eventId: String): ClipEntity?

    @Query(
        """
        SELECT * FROM clips
        WHERE deleted_at IS NULL
          AND (:matchAll = 1 OR content LIKE :pattern ESCAPE '\' )
        ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchVisible(matchAll: Int, pattern: String, limit: Int): List<ClipEntity>

    @Query(
        """
        SELECT * FROM clips
        WHERE deleted_at IS NULL
          AND (:matchAll = 1 OR content LIKE :pattern ESCAPE '\' )
        ORDER BY created_at DESC, origin_seq DESC, origin_device_id ASC, event_id ASC
        LIMIT :limit
        """,
    )
    fun observeSearchVisible(matchAll: Int, pattern: String, limit: Int): Flow<List<ClipEntity>>

    @Query(
        """
        UPDATE clips
        SET content = '', content_hash = '', source_app = NULL,
            deleted_at = :nowMs, terminal_reason = 'deleted'
        WHERE event_id = :eventId AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(eventId: String, nowMs: Long): Int

    @Query(
        """
        UPDATE clips
        SET content = '', content_hash = '', source_app = NULL,
            deleted_at = :nowMs, terminal_reason = 'deleted'
        WHERE deleted_at IS NULL
        """,
    )
    suspend fun softDeleteAllVisible(nowMs: Long): Int

    @Query("SELECT event_id FROM clips WHERE deleted_at IS NULL")
    suspend fun visibleEventIds(): List<String>

    @Query(
        """
        SELECT * FROM clips
        WHERE origin_device_id = :originDeviceId
          AND origin_seq >= :startSeq AND origin_seq <= :endSeq
        ORDER BY origin_seq
        LIMIT :limit
        """,
    )
    suspend fun inRange(originDeviceId: String, startSeq: Long, endSeq: Long, limit: Int): List<ClipEntity>

    @Query(
        """
        SELECT * FROM clips
        WHERE origin_device_id = :originDeviceId
          AND content_hash = :contentHash
          AND deleted_at IS NULL
          AND created_at > :afterMs
        ORDER BY created_at DESC
        LIMIT 1
        """,
    )
    suspend fun findRecentLiveByHash(originDeviceId: String, contentHash: String, afterMs: Long): ClipEntity?

    @Query(
        """
        SELECT content FROM clips
        WHERE content_hash = :contentHash AND deleted_at IS NULL
        LIMIT 1
        """,
    )
    suspend fun findLiveContentByHash(contentHash: String): String?
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: OutboxEntity): Long

    @Query(
        """
        SELECT * FROM outbox
        WHERE peer_id = :peerId AND state = 'pending'
        ORDER BY id
        """,
    )
    suspend fun pending(peerId: String): List<OutboxEntity>

    @Query(
        """
        UPDATE outbox
        SET state = 'announced', attempts = attempts + 1
        WHERE id IN (:ids)
        """,
    )
    suspend fun markAnnounced(ids: List<Long>)

    @Query("DELETE FROM outbox WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String)

    @Query("DELETE FROM outbox WHERE event_id IN (:eventIds)")
    suspend fun deleteByEventIds(eventIds: List<String>)

    @Query(
        """
        DELETE FROM outbox
        WHERE peer_id = :peerId AND event_id IN (
            SELECT event_id FROM clips
            WHERE origin_device_id = :originDeviceId
              AND origin_seq >= :startSeq AND origin_seq <= :endSeq
        )
        """,
    )
    suspend fun deleteInOriginRange(peerId: String, originDeviceId: String, startSeq: Long, endSeq: Long)

    @Query("UPDATE outbox SET state = 'pending' WHERE peer_id = :peerId AND state = 'announced'")
    suspend fun resetAnnouncedToPending(peerId: String)
}

@Dao
interface OriginReceiveStateDao {
    @Query("SELECT * FROM origin_receive_state WHERE origin_device_id = :originDeviceId LIMIT 1")
    suspend fun find(originDeviceId: String): OriginReceiveStateEntity?

    @Query("SELECT * FROM origin_receive_state")
    suspend fun all(): List<OriginReceiveStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OriginReceiveStateEntity)
}

@Dao
interface PeerCursorDao {
    @Query("SELECT * FROM peer_cursors WHERE peer_id = :peerId AND origin_device_id = :originDeviceId LIMIT 1")
    suspend fun find(peerId: String, originDeviceId: String): PeerCursorEntity?

    @Query("SELECT * FROM peer_cursors WHERE peer_id = :peerId")
    suspend fun allForPeer(peerId: String): List<PeerCursorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerCursorEntity)
}

@Dao
interface LocalSequenceDao {
    @Query("SELECT next_seq FROM local_sequences WHERE device_id = :deviceId")
    suspend fun getNextSeq(deviceId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalSequenceEntity)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingEntity)
}
