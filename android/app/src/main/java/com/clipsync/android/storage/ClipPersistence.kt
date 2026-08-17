package com.clipsync.android.storage

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Transactional access to the Room tables. Tests in this package may supply an in-memory
 * implementation of the same schema when Android SQLite is unavailable on the JVM.
 */
internal interface ClipPersistence {
    suspend fun <T> transaction(block: suspend ClipSession.() -> T): T

    suspend fun <T> read(block: suspend ClipSession.() -> T): T

    fun observeSearchVisible(query: String, limit: Int): Flow<List<ClipEntity>>

    fun observeSetting(key: String): Flow<String?>
}

internal interface ClipSession {
    suspend fun insertClip(entity: ClipEntity)
    suspend fun findClipByOriginSeq(originDeviceId: String, originSeq: Long): ClipEntity?
    suspend fun findClipByEventId(eventId: String): ClipEntity?
    suspend fun searchVisible(query: String, limit: Int): List<ClipEntity>
    suspend fun softDelete(eventId: String, nowMs: Long): Boolean
    suspend fun softDeleteAllVisible(nowMs: Long): List<String>
    suspend fun clipsInRange(originDeviceId: String, startSeq: Long, endSeq: Long, limit: Int): List<ClipEntity>
    suspend fun findRecentLiveByHash(originDeviceId: String, contentHash: String, afterMs: Long): ClipEntity?
    suspend fun findLiveContentByHash(contentHash: String): String?

    suspend fun allocateOriginSeq(deviceId: String): Long
    suspend fun receiveState(originDeviceId: String): OriginReceiveState
    suspend fun upsertReceiveState(originDeviceId: String, state: OriginReceiveState)
    suspend fun allReceiveStates(): Map<String, OriginReceiveState>

    suspend fun insertOutbox(peerId: String, eventId: String)
    suspend fun pendingOutbox(peerId: String): List<OutboxEntity>
    suspend fun markOutboxAnnounced(ids: List<Long>)
    suspend fun deleteOutboxForEvent(eventId: String)
    suspend fun deleteOutboxForEvents(eventIds: List<String>)
    suspend fun deleteOutboxInRange(peerId: String, originDeviceId: String, startSeq: Long, endSeq: Long)
    suspend fun resetOutboxToPending(peerId: String)

    suspend fun peerCursor(peerId: String, originDeviceId: String): OriginReceiveState
    suspend fun upsertPeerCursor(peerId: String, originDeviceId: String, state: OriginReceiveState, ackedAt: Long)
    suspend fun peerCursors(peerId: String): Map<String, OriginReceiveState>

    suspend fun getSetting(key: String): String?
    suspend fun setSetting(key: String, value: String)
}

internal class RoomClipPersistence(private val database: ClipDatabase) : ClipPersistence {
    private val session = RoomClipSession(database)

    override suspend fun <T> transaction(block: suspend ClipSession.() -> T): T =
        database.withTransaction { session.block() }

    override suspend fun <T> read(block: suspend ClipSession.() -> T): T = session.block()

    override fun observeSearchVisible(query: String, limit: Int): Flow<List<ClipEntity>> =
        if (query.isEmpty()) {
            database.clipDao().observeSearchVisible(matchAll = 1, pattern = "", limit = limit)
        } else {
            database.clipDao().observeSearchVisible(
                matchAll = 0,
                pattern = "%${escapeLike(query)}%",
                limit = limit,
            )
        }

    override fun observeSetting(key: String): Flow<String?> = database.settingDao().observe(key)
}

private class RoomClipSession(private val database: ClipDatabase) : ClipSession {
    override suspend fun insertClip(entity: ClipEntity) = database.clipDao().insert(entity)

    override suspend fun findClipByOriginSeq(originDeviceId: String, originSeq: Long): ClipEntity? =
        database.clipDao().findByOriginSeq(originDeviceId, originSeq)

    override suspend fun findClipByEventId(eventId: String): ClipEntity? =
        database.clipDao().findByEventId(eventId)

    override suspend fun searchVisible(query: String, limit: Int): List<ClipEntity> {
        if (query.isEmpty()) {
            return database.clipDao().searchVisible(matchAll = 1, pattern = "", limit = limit)
        }
        return database.clipDao().searchVisible(
            matchAll = 0,
            pattern = "%${escapeLike(query)}%",
            limit = limit,
        )
    }

    override suspend fun softDelete(eventId: String, nowMs: Long): Boolean =
        database.clipDao().softDelete(eventId, nowMs) == 1

    override suspend fun softDeleteAllVisible(nowMs: Long): List<String> {
        val ids = database.clipDao().visibleEventIds()
        if (ids.isNotEmpty()) {
            database.clipDao().softDeleteAllVisible(nowMs)
        }
        return ids
    }

    override suspend fun clipsInRange(
        originDeviceId: String,
        startSeq: Long,
        endSeq: Long,
        limit: Int,
    ): List<ClipEntity> = database.clipDao().inRange(originDeviceId, startSeq, endSeq, limit)

    override suspend fun findRecentLiveByHash(
        originDeviceId: String,
        contentHash: String,
        afterMs: Long,
    ): ClipEntity? = database.clipDao().findRecentLiveByHash(originDeviceId, contentHash, afterMs)

    override suspend fun findLiveContentByHash(contentHash: String): String? =
        database.clipDao().findLiveContentByHash(contentHash)

    override suspend fun allocateOriginSeq(deviceId: String): Long {
        val next = database.localSequenceDao().getNextSeq(deviceId) ?: 1L
        database.localSequenceDao().upsert(LocalSequenceEntity(deviceId, next + 1))
        return next
    }

    override suspend fun receiveState(originDeviceId: String): OriginReceiveState =
        database.originReceiveStateDao().find(originDeviceId).toState()

    override suspend fun upsertReceiveState(originDeviceId: String, state: OriginReceiveState) {
        database.originReceiveStateDao().upsert(
            OriginReceiveStateEntity(
                originDeviceId = originDeviceId,
                contiguousSeq = state.contiguousSeq,
                receivedRanges = SequenceRangeJson.serialize(state.receivedRanges),
            ),
        )
    }

    override suspend fun allReceiveStates(): Map<String, OriginReceiveState> =
        database.originReceiveStateDao().all().associate { it.originDeviceId to it.toState() }

    override suspend fun insertOutbox(peerId: String, eventId: String) {
        database.outboxDao().insert(
            OutboxEntity(peerId = peerId, eventId = eventId, state = OUTBOX_PENDING),
        )
    }

    override suspend fun pendingOutbox(peerId: String): List<OutboxEntity> =
        database.outboxDao().pending(peerId)

    override suspend fun markOutboxAnnounced(ids: List<Long>) {
        if (ids.isNotEmpty()) {
            database.outboxDao().markAnnounced(ids)
        }
    }

    override suspend fun deleteOutboxForEvent(eventId: String) {
        database.outboxDao().deleteByEventId(eventId)
    }

    override suspend fun deleteOutboxForEvents(eventIds: List<String>) {
        if (eventIds.isNotEmpty()) {
            database.outboxDao().deleteByEventIds(eventIds)
        }
    }

    override suspend fun deleteOutboxInRange(
        peerId: String,
        originDeviceId: String,
        startSeq: Long,
        endSeq: Long,
    ) {
        database.outboxDao().deleteInOriginRange(peerId, originDeviceId, startSeq, endSeq)
    }

    override suspend fun resetOutboxToPending(peerId: String) {
        database.outboxDao().resetAnnouncedToPending(peerId)
    }

    override suspend fun peerCursor(peerId: String, originDeviceId: String): OriginReceiveState =
        database.peerCursorDao().find(peerId, originDeviceId).toPeerState()

    override suspend fun upsertPeerCursor(
        peerId: String,
        originDeviceId: String,
        state: OriginReceiveState,
        ackedAt: Long,
    ) {
        database.peerCursorDao().upsert(
            PeerCursorEntity(
                peerId = peerId,
                originDeviceId = originDeviceId,
                receivedSeq = state.contiguousSeq,
                ackedAt = ackedAt,
                receivedRanges = SequenceRangeJson.serialize(state.receivedRanges),
            ),
        )
    }

    override suspend fun peerCursors(peerId: String): Map<String, OriginReceiveState> =
        database.peerCursorDao().allForPeer(peerId).associate { it.originDeviceId to it.toPeerState() }

    override suspend fun getSetting(key: String): String? = database.settingDao().get(key)

    override suspend fun setSetting(key: String, value: String) {
        database.settingDao().upsert(SettingEntity(key, value))
    }
}

private fun OriginReceiveStateEntity?.toState(): OriginReceiveState =
    if (this == null) {
        OriginReceiveState.EMPTY
    } else {
        OriginReceiveState(contiguousSeq, SequenceRangeJson.deserialize(receivedRanges))
    }

private fun PeerCursorEntity?.toPeerState(): OriginReceiveState =
    if (this == null) {
        OriginReceiveState.EMPTY
    } else {
        OriginReceiveState(receivedSeq, SequenceRangeJson.deserialize(receivedRanges))
    }

internal fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
