package com.clipsync.android.storage

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory stand-in for the Room schema. Used by JVM unit tests because Android SQLite
 * (Room.inMemoryDatabaseBuilder) requires Robolectric or instrumentation, which are not
 * on this module's classpath.
 */
internal class InMemoryClipPersistence : ClipPersistence {
    private val mutex = Mutex()
    private val clips = LinkedHashMap<String, ClipEntity>()
    private val originIndex = HashMap<Pair<String, Long>, String>()
    private val outbox = LinkedHashMap<Long, OutboxEntity>()
    private val receiveStates = LinkedHashMap<String, OriginReceiveStateEntity>()
    private val peerCursors = LinkedHashMap<Pair<String, String>, PeerCursorEntity>()
    private val sequences = LinkedHashMap<String, Long>()
    private val settings = LinkedHashMap<String, String>()
    private var nextOutboxId = 1L
    private val session = InMemoryClipSession()
    private val invalidations = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        invalidations.tryEmit(Unit)
    }

    override suspend fun <T> transaction(block: suspend ClipSession.() -> T): T {
        val result = mutex.withLock {
            val snapshot = Snapshot(
                clips = HashMap(clips),
                originIndex = HashMap(originIndex),
                outbox = HashMap(outbox),
                receiveStates = HashMap(receiveStates),
                peerCursors = HashMap(peerCursors),
                sequences = HashMap(sequences),
                settings = HashMap(settings),
                nextOutboxId = nextOutboxId,
            )
            try {
                session.block()
            } catch (error: Throwable) {
                restore(snapshot)
                throw error
            }
        }
        // Emit only after the mutex is released so Unconfined collectors cannot deadlock.
        invalidations.tryEmit(Unit)
        return result
    }

    override suspend fun <T> read(block: suspend ClipSession.() -> T): T =
        mutex.withLock { session.block() }

    override fun observeSearchVisible(query: String, limit: Int): Flow<List<ClipEntity>> = flow {
        invalidations.collect {
            emit(mutex.withLock { session.searchVisible(query, limit) })
        }
    }

    override fun observeSetting(key: String): Flow<String?> = flow {
        invalidations.collect {
            emit(mutex.withLock { session.getSetting(key) })
        }
    }

    override fun observeOutboxPendingCount(peerId: String): Flow<Int> = flow {
        invalidations.collect {
            emit(
                mutex.withLock {
                    outbox.values.count { it.peerId == peerId && it.state == OUTBOX_PENDING }
                },
            )
        }
    }

    private fun restore(snapshot: Snapshot) {
        clips.clear()
        clips.putAll(snapshot.clips)
        originIndex.clear()
        originIndex.putAll(snapshot.originIndex)
        outbox.clear()
        outbox.putAll(snapshot.outbox)
        receiveStates.clear()
        receiveStates.putAll(snapshot.receiveStates)
        peerCursors.clear()
        peerCursors.putAll(snapshot.peerCursors)
        sequences.clear()
        sequences.putAll(snapshot.sequences)
        settings.clear()
        settings.putAll(snapshot.settings)
        nextOutboxId = snapshot.nextOutboxId
    }

    private inner class InMemoryClipSession : ClipSession {
        override suspend fun insertClip(entity: ClipEntity) {
            val key = entity.originDeviceId to entity.originSeq
            require(entity.eventId !in clips) { "duplicate event_id" }
            require(key !in originIndex) { "duplicate origin sequence" }
            clips[entity.eventId] = entity
            originIndex[key] = entity.eventId
        }

        override suspend fun findClipByOriginSeq(originDeviceId: String, originSeq: Long): ClipEntity? {
            val eventId = originIndex[originDeviceId to originSeq] ?: return null
            return clips[eventId]
        }

        override suspend fun findClipByEventId(eventId: String): ClipEntity? = clips[eventId]

        override suspend fun searchVisible(query: String, limit: Int): List<ClipEntity> =
            clips.values
                .filter { it.deletedAt == null }
                .filter { query.isEmpty() || it.content?.contains(query, ignoreCase = true) == true }
                .sortedWith(
                    compareByDescending<ClipEntity> { it.createdAt }
                        .thenByDescending { it.originSeq }
                        .thenBy { it.originDeviceId }
                        .thenBy { it.eventId },
                )
                .take(limit)

        override suspend fun softDelete(eventId: String, nowMs: Long): Boolean {
            val existing = clips[eventId] ?: return false
            if (existing.deletedAt != null) {
                return false
            }
            clips[eventId] = existing.copy(
                content = "",
                contentHash = "",
                sourceApp = null,
                deletedAt = nowMs,
                terminalReason = TerminalReasons.DELETED,
            )
            return true
        }

        override suspend fun softDeleteAllVisible(nowMs: Long): List<String> {
            val ids = clips.values.filter { it.deletedAt == null }.map { it.eventId }
            for (id in ids) {
                softDelete(id, nowMs)
            }
            return ids
        }

        override suspend fun clipsInRange(
            originDeviceId: String,
            startSeq: Long,
            endSeq: Long,
            limit: Int,
        ): List<ClipEntity> =
            clips.values
                .filter { it.originDeviceId == originDeviceId && it.originSeq in startSeq..endSeq }
                .sortedBy { it.originSeq }
                .take(limit)

        override suspend fun findRecentLiveByHash(
            originDeviceId: String,
            contentHash: String,
            afterMs: Long,
        ): ClipEntity? =
            clips.values
                .filter {
                    it.originDeviceId == originDeviceId &&
                        it.contentHash == contentHash &&
                        it.deletedAt == null &&
                        it.createdAt > afterMs
                }
                .maxByOrNull { it.createdAt }

        override suspend fun findLiveContentByHash(contentHash: String): String? =
            clips.values.firstOrNull { it.contentHash == contentHash && it.deletedAt == null }?.content

        override suspend fun allocateOriginSeq(deviceId: String): Long {
            val next = sequences[deviceId] ?: 1L
            sequences[deviceId] = next + 1
            return next
        }

        override suspend fun receiveState(originDeviceId: String): OriginReceiveState =
            receiveStates[originDeviceId].toState()

        override suspend fun upsertReceiveState(originDeviceId: String, state: OriginReceiveState) {
            receiveStates[originDeviceId] = OriginReceiveStateEntity(
                originDeviceId = originDeviceId,
                contiguousSeq = state.contiguousSeq,
                receivedRanges = SequenceRangeJson.serialize(state.receivedRanges),
            )
        }

        override suspend fun allReceiveStates(): Map<String, OriginReceiveState> =
            receiveStates.mapValues { it.value.toState() }

        override suspend fun insertOutbox(peerId: String, eventId: String) {
            val duplicate = outbox.values.any { it.peerId == peerId && it.eventId == eventId }
            if (duplicate) {
                return
            }
            val id = nextOutboxId++
            outbox[id] = OutboxEntity(id = id, peerId = peerId, eventId = eventId, state = OUTBOX_PENDING)
        }

        override suspend fun pendingOutbox(peerId: String): List<OutboxEntity> =
            outbox.values.filter { it.peerId == peerId && it.state == OUTBOX_PENDING }.sortedBy { it.id }

        override suspend fun markOutboxAnnounced(ids: List<Long>) {
            for (id in ids) {
                val existing = outbox[id] ?: continue
                outbox[id] = existing.copy(state = OUTBOX_ANNOUNCED, attempts = existing.attempts + 1)
            }
        }

        override suspend fun deleteOutboxForEvent(eventId: String) {
            outbox.entries.removeAll { it.value.eventId == eventId }
        }

        override suspend fun deleteOutboxForEvents(eventIds: List<String>) {
            val set = eventIds.toSet()
            outbox.entries.removeAll { it.value.eventId in set }
        }

        override suspend fun deleteOutboxInRange(
            peerId: String,
            originDeviceId: String,
            startSeq: Long,
            endSeq: Long,
        ) {
            val eventIds = clips.values
                .filter { it.originDeviceId == originDeviceId && it.originSeq in startSeq..endSeq }
                .map { it.eventId }
                .toSet()
            outbox.entries.removeAll { it.value.peerId == peerId && it.value.eventId in eventIds }
        }

        override suspend fun resetOutboxToPending(peerId: String) {
            for ((id, entry) in outbox) {
                if (entry.peerId == peerId && entry.state == OUTBOX_ANNOUNCED) {
                    outbox[id] = entry.copy(state = OUTBOX_PENDING)
                }
            }
        }

        override suspend fun peerCursor(peerId: String, originDeviceId: String): OriginReceiveState =
            peerCursors[peerId to originDeviceId].toPeerState()

        override suspend fun upsertPeerCursor(
            peerId: String,
            originDeviceId: String,
            state: OriginReceiveState,
            ackedAt: Long,
        ) {
            peerCursors[peerId to originDeviceId] = PeerCursorEntity(
                peerId = peerId,
                originDeviceId = originDeviceId,
                receivedSeq = state.contiguousSeq,
                ackedAt = ackedAt,
                receivedRanges = SequenceRangeJson.serialize(state.receivedRanges),
            )
        }

        override suspend fun peerCursors(peerId: String): Map<String, OriginReceiveState> =
            peerCursors.filterKeys { it.first == peerId }
                .map { it.value.originDeviceId to it.value.toPeerState() }
                .toMap()

        override suspend fun getSetting(key: String): String? = settings[key]

        override suspend fun setSetting(key: String, value: String) {
            settings[key] = value
        }

        override suspend fun hardDeleteExpiredLive(cutoffMs: Long): Int {
            val pendingIds = pendingOutboxEventIds()
            val victims =
                clips.values.filter { row ->
                    row.deletedAt == null &&
                        row.createdAt < cutoffMs &&
                        row.eventId !in pendingIds
                }
            for (row in victims) {
                removeClip(row)
            }
            return victims.size
        }

        override suspend fun hardDeleteExpiredTombstones(cutoffMs: Long): Int {
            val pendingIds = pendingOutboxEventIds()
            val victims =
                clips.values.filter { row ->
                    val deletedAt = row.deletedAt
                    deletedAt != null &&
                        deletedAt < cutoffMs &&
                        row.eventId !in pendingIds
                }
            for (row in victims) {
                removeClip(row)
            }
            return victims.size
        }

        private fun pendingOutboxEventIds(): Set<String> =
            outbox.values
                .map { it.eventId }
                .toSet()

        override suspend fun advanceOriginSeq(
            deviceId: String,
            originSeq: Long,
        ) {
            val current = sequences[deviceId] ?: 1L
            sequences[deviceId] = maxOf(current, originSeq + 1)
        }

        override suspend fun deleteOutboxForPeer(peerId: String) {
            outbox.entries.removeAll { it.value.peerId == peerId }
        }

        private fun removeClip(row: ClipEntity) {
            clips.remove(row.eventId)
            originIndex.remove(row.originDeviceId to row.originSeq)
        }
    }

    private data class Snapshot(
        val clips: Map<String, ClipEntity>,
        val originIndex: Map<Pair<String, Long>, String>,
        val outbox: Map<Long, OutboxEntity>,
        val receiveStates: Map<String, OriginReceiveStateEntity>,
        val peerCursors: Map<Pair<String, String>, PeerCursorEntity>,
        val sequences: Map<String, Long>,
        val settings: Map<String, String>,
        val nextOutboxId: Long,
    )
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
