package com.clipsync.android.storage

import androidx.room.withTransaction
import com.clipsync.android.sync.OriginReceiveState
import com.clipsync.android.sync.SequenceRange
import com.clipsync.android.sync.SequenceRangeJson
import java.util.UUID

/**
 * The single storage entry point for the sync engine and UI. Mirrors the Windows
 * `SqliteClipboardEventStore` semantics: events commit locally (with sequence allocation,
 * receive-vector advance, and outbox fan-out) in one transaction before any network send;
 * remote stores are idempotent on `(origin_device_id, origin_seq)`; deletes are local soft
 * deletes that keep terminal markers so peers' sequence gaps still close.
 */
class ClipSyncRepository(
    private val database: ClipSyncDatabase,
    val localDeviceId: String,
) {
    init {
        require(localDeviceId.isNotBlank()) { "localDeviceId cannot be blank" }
    }

    private val clips get() = database.clipEvents()
    private val outbox get() = database.outbox()
    private val cursors get() = database.peerCursors()
    private val receiveState get() = database.originReceiveState()
    private val sequences get() = database.localSequences()

    // ---- Local capture ----

    /**
     * Commits a locally captured clip: allocates the next origin sequence, stores the row,
     * advances this device's own receive vector, and enqueues one outbox row per peer, all
     * atomically. The event only reaches the network after this returns.
     */
    suspend fun storeLocalEvent(
        draft: LocalClipDraft,
        fanOutPeerIds: List<String>,
    ): StoredClipEvent = database.withTransaction {
        val originSeq = allocateSequence()
        val eventId = UUID.randomUUID().toString()
        clips.insert(
            ClipEventEntity(
                eventId = eventId,
                originDeviceId = localDeviceId,
                originSeq = originSeq,
                content = draft.content,
                contentHash = draft.contentHash,
                sourceApp = draft.sourceApp,
                createdAtMs = draft.capturedAtMs,
                expiresAtMs = draft.expiresAtMs,
                deletedAtMs = null,
                terminalReason = null,
                appliedAtMs = null,
            ),
        )
        advanceReceiveState(localDeviceId, originSeq)
        enqueueOutbox(eventId, localDeviceId, originSeq, fanOutPeerIds, excludedPeerId = null)
        StoredClipEvent(eventId, localDeviceId, originSeq)
    }

    // ---- Remote inbox ----

    /**
     * Persists a peer-delivered event idempotently. Retries of the identical event return
     * [RemoteStoreResult.AlreadyPersisted]; a different identity under the same key returns
     * [RemoteStoreResult.IdentityConflict], which the protocol treats as fatal.
     */
    suspend fun storeRemoteEvent(
        remoteEvent: RemoteClipEvent,
        sourcePeerId: String?,
        fanOutPeerIds: List<String> = emptyList(),
    ): RemoteStoreResult {
        require(remoteEvent.originDeviceId != localDeviceId) {
            "Remote events cannot claim this device as origin."
        }
        return database.withTransaction {
            checkRemoteIdentity(
                remoteEvent.eventId,
                remoteEvent.originDeviceId,
                remoteEvent.originSeq,
                expectedContentHash = remoteEvent.contentHash,
            )?.let { return@withTransaction it }

            clips.insert(
                ClipEventEntity(
                    eventId = remoteEvent.eventId,
                    originDeviceId = remoteEvent.originDeviceId,
                    originSeq = remoteEvent.originSeq,
                    content = remoteEvent.content,
                    contentHash = remoteEvent.contentHash,
                    sourceApp = remoteEvent.sourceApp,
                    createdAtMs = remoteEvent.createdAtMs,
                    expiresAtMs = remoteEvent.expiresAtMs,
                    deletedAtMs = null,
                    terminalReason = null,
                    appliedAtMs = null,
                ),
            )
            val state = advanceReceiveState(remoteEvent.originDeviceId, remoteEvent.originSeq)
            enqueueOutbox(
                remoteEvent.eventId,
                remoteEvent.originDeviceId,
                remoteEvent.originSeq,
                fanOutPeerIds,
                excludedPeerId = sourcePeerId,
            )
            RemoteStoreResult.Stored(state)
        }
    }

    /** Persists an origin-authoritative unavailable marker; advances cursors without content. */
    suspend fun storeRemoteTerminal(
        marker: RemoteTerminalMarker,
        sourcePeerId: String?,
        receivedAtMs: Long,
        fanOutPeerIds: List<String> = emptyList(),
    ): RemoteStoreResult {
        require(marker.originDeviceId != localDeviceId) {
            "Terminal markers cannot claim this device as origin."
        }
        return database.withTransaction {
            checkRemoteIdentity(
                marker.eventId,
                marker.originDeviceId,
                marker.originSeq,
                expectedContentHash = null,
            )?.let { return@withTransaction it }

            clips.insert(
                ClipEventEntity(
                    eventId = marker.eventId,
                    originDeviceId = marker.originDeviceId,
                    originSeq = marker.originSeq,
                    content = "",
                    contentHash = "",
                    sourceApp = null,
                    createdAtMs = receivedAtMs,
                    expiresAtMs = null,
                    deletedAtMs = receivedAtMs,
                    terminalReason = marker.reason,
                    appliedAtMs = null,
                ),
            )
            val state = advanceReceiveState(marker.originDeviceId, marker.originSeq)
            enqueueOutbox(
                marker.eventId,
                marker.originDeviceId,
                marker.originSeq,
                fanOutPeerIds,
                excludedPeerId = sourcePeerId,
            )
            RemoteStoreResult.Stored(state)
        }
    }

    // ---- History ----

    suspend fun searchHistory(query: HistoryQuery = HistoryQuery()): List<ClipHistoryEntry> {
        val pattern = query.searchText
            ?.takeIf { it.isNotEmpty() }
            ?.let { "%${escapeLikePattern(it)}%" }
        return clips.search(pattern, query.limit, query.offset).map { it.toHistoryEntry() }
    }

    suspend fun getById(eventId: String, includeDeleted: Boolean = false): ClipHistoryEntry? =
        clips.getByEventId(eventId, includeDeleted)?.toHistoryEntry()

    /** Records that a remote event's text reached this device's system clipboard. */
    suspend fun markApplied(eventId: String, appliedAtMs: Long): Boolean =
        clips.markApplied(eventId, appliedAtMs) == 1

    /**
     * Local delete: erases the content but keeps the row as a `deleted` terminal marker so the
     * same acknowledged event never reappears and peers' gap requests get a terminal answer.
     * A still-queued outbox row now announces `unavailable` instead of the body.
     */
    suspend fun deleteEvent(eventId: String, deletedAtMs: Long): Boolean =
        clips.softDelete(eventId, deletedAtMs) == 1

    /** Clears all visible history the same way [deleteEvent] does; returns rows affected. */
    suspend fun clearHistory(deletedAtMs: Long): Int = clips.softDeleteAll(deletedAtMs)

    /** Expires rows older than the policy age or beyond the entry cap; returns rows affected. */
    suspend fun cleanup(policy: RetentionPolicy, nowMs: Long): Int = database.withTransaction {
        clips.cleanup(nowMs - policy.maximumAgeMs, policy.maximumEntries, nowMs)
    }

    // ---- Sync projection ----

    /** Every origin's persisted receive state, including this device's own contiguous history. */
    suspend fun knownVector(): Map<String, OriginReceiveState> =
        receiveState.all().associate { it.originDeviceId to it.toState() }

    /** Rows for the requested ranges of one origin, capped to [maximumEvents], ordered by sequence. */
    suspend fun getSyncableEvents(
        originDeviceId: String,
        ranges: List<SequenceRange>,
        maximumEvents: Int,
    ): List<SyncableClipEvent> {
        require(maximumEvents >= 1) { "maximumEvents must be positive." }
        val events = mutableListOf<SyncableClipEvent>()
        for (range in ranges) {
            if (events.size >= maximumEvents) {
                break
            }
            clips.syncableInRange(originDeviceId, range.startSeq, range.endSeq, maximumEvents - events.size)
                .mapTo(events) { it.toSyncable() }
        }
        return events
    }

    suspend fun getSyncableEventsByIds(eventIds: List<String>): List<SyncableClipEvent> =
        if (eventIds.isEmpty()) emptyList() else clips.syncableByIds(eventIds).map { it.toSyncable() }

    /** Finds live content with the given hash so an announced event can be materialized without a fetch. */
    suspend fun findLiveContentByHash(contentHash: String): String? =
        clips.findLiveContentByHash(contentHash)

    // ---- Outbox ----

    suspend fun outboxBatch(peerId: String, limit: Int): List<OutboxBatchItem> {
        require(limit >= 1) { "limit must be positive." }
        return outbox.pendingBatch(peerId, limit).map { row ->
            OutboxBatchItem(
                outboxId = row.outboxId,
                peerId = row.peerId,
                state = row.state,
                attempts = row.attempts,
                event = row.clip.toSyncable(),
            )
        }
    }

    suspend fun markOutboxAnnounced(outboxIds: List<Long>) {
        if (outboxIds.isNotEmpty()) {
            outbox.markAnnounced(outboxIds)
        }
    }

    /** Returns announced-but-unacked entries to pending, e.g. at the start of a new session. */
    suspend fun resetOutboxToPending(peerId: String) = outbox.resetToPending(peerId)

    suspend fun pendingOutboxCount(peerId: String): Int = outbox.pendingCount(peerId)

    suspend fun totalPendingOutboxCount(): Int = outbox.totalPendingCount()

    /**
     * Removes outbox rows the peer has persisted according to its acks or known vector, and
     * advances the peer cursor, in one transaction.
     */
    suspend fun applyPeerAckRanges(
        peerId: String,
        acks: List<OriginAckRanges>,
        nowMs: Long,
    ) {
        if (acks.isEmpty()) {
            return
        }
        database.withTransaction {
            for (ack in acks) {
                var cursor = cursors.get(peerId, ack.originDeviceId)?.toState()
                    ?: OriginReceiveState.EMPTY
                for (range in ack.ranges) {
                    cursor = cursor.acceptRange(range)
                }
                cursors.upsert(
                    PeerCursorEntity(
                        peerId = peerId,
                        originDeviceId = ack.originDeviceId,
                        contiguousSeq = cursor.contiguousSeq,
                        receivedRangesJson = SequenceRangeJson.serialize(cursor.receivedRanges),
                        updatedAtMs = nowMs,
                    ),
                )
                for (range in ack.ranges) {
                    outbox.deleteAckedRange(peerId, ack.originDeviceId, range.startSeq, range.endSeq)
                }
            }
        }
    }

    suspend fun peerCursors(peerId: String): Map<String, OriginReceiveState> =
        cursors.listForPeer(peerId).associate { it.originDeviceId to it.toState() }

    /** Drops all queue and cursor state for a revoked/unpaired peer; history is untouched. */
    suspend fun forgetPeer(peerId: String) = database.withTransaction {
        outbox.deleteForPeer(peerId)
        cursors.deleteForPeer(peerId)
    }

    // ---- Internals (all callers hold the enclosing transaction) ----

    private suspend fun allocateSequence(): Long {
        val next = sequences.nextSeq(localDeviceId) ?: 1L
        sequences.upsert(LocalSequenceEntity(localDeviceId, next + 1))
        return next
    }

    private suspend fun advanceReceiveState(originDeviceId: String, originSeq: Long): OriginReceiveState {
        val state = (receiveState.get(originDeviceId)?.toState() ?: OriginReceiveState.EMPTY)
            .accept(originSeq)
        receiveState.upsert(
            OriginReceiveStateEntity(
                originDeviceId = originDeviceId,
                contiguousSeq = state.contiguousSeq,
                receivedRangesJson = SequenceRangeJson.serialize(state.receivedRanges),
            ),
        )
        return state
    }

    private suspend fun enqueueOutbox(
        eventId: String,
        originDeviceId: String,
        originSeq: Long,
        peerIds: List<String>,
        excludedPeerId: String?,
    ) {
        val entries = peerIds
            .distinct()
            .filter { it != originDeviceId && it != excludedPeerId }
            .map { peerId ->
                OutboxEntryEntity(
                    peerId = peerId,
                    eventId = eventId,
                    originDeviceId = originDeviceId,
                    originSeq = originSeq,
                )
            }
        if (entries.isNotEmpty()) {
            outbox.insertAll(entries)
        }
    }

    private suspend fun checkRemoteIdentity(
        eventId: String,
        originDeviceId: String,
        originSeq: Long,
        expectedContentHash: String?,
    ): RemoteStoreResult? {
        val byKey = clips.getByOriginSeq(originDeviceId, originSeq)
        if (byKey != null) {
            if (byKey.eventId != eventId) {
                return RemoteStoreResult.IdentityConflict("origin sequence maps to a different event id")
            }
            val isTerminal = byKey.deletedAtMs != null
            if (!isTerminal && expectedContentHash != null && byKey.contentHash != expectedContentHash) {
                return RemoteStoreResult.IdentityConflict("origin sequence maps to different content")
            }
            return RemoteStoreResult.AlreadyPersisted
        }

        return if (clips.getByEventId(eventId, includeDeleted = true) == null) {
            null
        } else {
            RemoteStoreResult.IdentityConflict("event id maps to a different origin sequence")
        }
    }

    private fun PeerCursorEntity.toState(): OriginReceiveState =
        OriginReceiveState(contiguousSeq, SequenceRangeJson.deserialize(receivedRangesJson))

    private fun OriginReceiveStateEntity.toState(): OriginReceiveState =
        OriginReceiveState(contiguousSeq, SequenceRangeJson.deserialize(receivedRangesJson))

    private companion object {
        fun escapeLikePattern(value: String): String = value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
