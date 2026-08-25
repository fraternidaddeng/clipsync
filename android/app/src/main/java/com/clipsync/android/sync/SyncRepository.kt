package com.clipsync.android.sync

import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.media.ValidatedImage

/**
 * A remote clip body being committed locally (from clip_payload, an image chunk transfer, or a
 * hash replay). Image events carry an empty [content] and the blob metadata; the encoded bytes
 * are already committed in the media store before this is persisted.
 */
data class RemoteClipEvent(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val kind: String = MediaLimits.KIND_TEXT,
    val mimeType: String? = null,
    val encodedBytes: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
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

/** One event this device can serve to the peer: a live text/image body or a terminal marker. */
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
    val kind: String = MediaLimits.KIND_TEXT,
    // Image blob metadata (protocol v2); null on text rows and terminal markers.
    val mimeType: String? = null,
    val encodedBytes: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
) {
    val isImage: Boolean get() = kind == MediaLimits.KIND_IMAGE
}

/** One pending outbox row: a local event queued for announcement to the paired peer. */
data class OutboxRow(val entryId: Long, val event: SyncableClipEvent)

/** Canonical acknowledged/held ranges for one origin. */
data class OriginSequenceRanges(val originDeviceId: String, val ranges: List<SequenceRange>)

/** A remote clip body that committed locally during a session, in commit order. */
data class RemoteClipApplied(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    /** Empty for image clips; the bytes live in the media store under [contentHash]. */
    val content: String,
    val createdAtMs: Long,
    val kind: String = MediaLimits.KIND_TEXT,
    val contentHash: String? = null,
    val mimeType: String? = null,
) {
    val isImage: Boolean get() = kind == MediaLimits.KIND_IMAGE
}

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

    // ---- Protocol v2 image support (optional; text-only repositories keep the defaults) ----

    /** Media blob store for protocol v2 image bodies; null when images are unsupported. */
    val media: MediaBlobStore? get() = null

    /** True when a live image row with this blob hash exists and its bytes are on disk. */
    suspend fun findLiveImageByHash(contentHash: String): Boolean = false

    /**
     * Captures one local clipboard image as a new origin event and queues it for the peer.
     * The encoded bytes were already validated and committed into [media]; this only writes
     * the event and metadata rows. Returns null when images are unsupported.
     */
    suspend fun recordLocalImageClip(
        image: ValidatedImage,
        sourceApp: String?,
        nowMs: Long,
    ): SyncableClipEvent? = null

    /**
     * Marks live local images announced to the peer as `local_only` terminal markers because
     * the session cannot carry image bodies (ADR 0005 §4), so history can badge them
     * 仅本机保留 (ADR 0005 §5). First mark wins; deleted/text rows are ignored.
     */
    suspend fun markImagesLocalOnly(eventIds: List<String>, nowMs: Long) {}

    /** Clears the 仅本机保留 mark when a v2 session announces the image as available again. */
    suspend fun clearImagesLocalOnly(eventIds: List<String>) {}
}
