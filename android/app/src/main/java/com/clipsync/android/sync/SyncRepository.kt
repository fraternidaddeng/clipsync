package com.clipsync.android.sync

/** A remote clip body being committed locally (from clip_payload or a hash replay). */
data class RemoteClipEvent(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
)

/** An origin-authoritative unavailable marker: advances sync without carrying content. */
data class RemoteTerminalMarker(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val reason: String,
)

/** Outcome of persisting one remote event or terminal marker. */
sealed interface RemoteStoreResult {
    /** Newly committed together with its receive-state advance. */
    data object Stored : RemoteStoreResult

    /** The same identity was already persisted; success for idempotent retries. */
    data object Duplicate : RemoteStoreResult

    /** The idempotency key exists with a different identity (protocol section 5). */
    data class IdentityConflict(val detail: String) : RemoteStoreResult
}

/** One event this device can serve to the peer: a live text body or a terminal marker. */
data class SyncableClipEvent(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val isTerminal: Boolean,
    val terminalReason: String? = null,
    val content: String? = null,
    val contentHash: String? = null,
    val sourceApp: String? = null,
    val createdAtMs: Long = 0,
    val expiresAtMs: Long? = null,
)

/** One pending outbox row: a local event queued for announcement to the paired peer. */
data class OutboxRow(val entryId: Long, val event: SyncableClipEvent)

/** Canonical acknowledged/held ranges for one origin. */
data class OriginSequenceRanges(val originDeviceId: String, val ranges: List<SequenceRange>)

/** A remote clip body that committed locally during a session, in commit order. */
data class RemoteClipApplied(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val createdAtMs: Long,
)

/**
 * Storage contract between [SyncEngine] and the clipboard event store.
 *
 * COORDINATION NOTE: the Room-backed implementation is expected to land separately; until it
 * does, [InMemorySyncRepository] keeps the sync stack functional. A persistent implementation
 * must make each store/ack call one local transaction (protocol v1 `ack_ranges` semantics:
 * acknowledge only after the event and cursor state committed together).
 */
interface SyncRepository {
    /** Per-origin receive progress, including this device's own published sequence. */
    suspend fun knownVector(): Map<String, OriginReceiveState>

    /** A live (non-expired, non-deleted) local body with this hash, for replay without refetch. */
    suspend fun findLiveContentByHash(contentHash: String): String?

    suspend fun storeRemoteEvent(event: RemoteClipEvent, viaDeviceId: String): RemoteStoreResult

    suspend fun storeRemoteTerminal(marker: RemoteTerminalMarker, viaDeviceId: String): RemoteStoreResult

    /** Events of [originDeviceId] inside [ranges], ordered by sequence, at most [limit]. */
    suspend fun getSyncableEvents(
        originDeviceId: String,
        ranges: List<SequenceRange>,
        limit: Int,
    ): List<SyncableClipEvent>

    suspend fun getSyncableEventsByIds(eventIds: List<String>): List<SyncableClipEvent>

    /** Records what [peerDeviceId] provably holds; prunes those events from its outbox. */
    suspend fun applyPeerAckRanges(peerDeviceId: String, ranges: List<OriginSequenceRanges>, nowMs: Long)

    /** Reverts announced-but-unacked outbox rows to pending at session start. */
    suspend fun resetOutboxToPending(peerDeviceId: String)

    /** Pending outbox rows for [peerDeviceId], ordered by entry id, at most [limit]. */
    suspend fun getOutboxBatch(peerDeviceId: String, limit: Int): List<OutboxRow>

    suspend fun markOutboxAnnounced(entryIds: List<Long>)

    /**
     * Captures one local clipboard text as a new origin event and queues it for the peer.
     * Returns null when the text is empty or exceeds the 1 MiB protocol limit.
     */
    suspend fun recordLocalClip(text: String, sourceApp: String?, nowMs: Long): SyncableClipEvent?
}
