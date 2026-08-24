package com.clipsync.android.storage

import com.clipsync.android.sync.OriginReceiveState
import com.clipsync.android.sync.SequenceRange

/** Protocol v1/v2 terminal reasons for sequences whose content is permanently unavailable. */
object TerminalReasons {
    const val LOCAL_ONLY = "local_only"
    const val DELETED = "deleted"
    const val EXPIRED = "expired"
    const val POLICY_FILTERED = "policy_filtered"
    const val NOT_FOUND = "not_found"

    /** Protocol v2 only: an image the peer or local policy refuses to carry. */
    const val UNSUPPORTED_MEDIA = "unsupported_media"

    val ALL = setOf(LOCAL_ONLY, DELETED, EXPIRED, POLICY_FILTERED, NOT_FOUND, UNSUPPORTED_MEDIA)
}

/** A locally captured text ready to persist; the caller has already hashed and policy-checked it. */
data class LocalClipDraft(
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val capturedAtMs: Long,
    val expiresAtMs: Long? = null,
)

/**
 * A locally captured image whose blob the caller has already committed into the
 * [com.clipsync.android.media.MediaBlobStore]; this draft only carries the validated metadata.
 */
data class LocalImageDraft(
    val contentHash: String,
    val mimeType: String,
    val encodedBytes: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val sourceApp: String?,
    val capturedAtMs: Long,
    val expiresAtMs: Long? = null,
)

/** Blob metadata attached to an image clip, straight from the `media_blobs` row. */
data class ClipMediaRef(
    val contentHash: String,
    val mimeType: String,
    val encodedBytes: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

/** Identity of a committed local event; the sequence is allocated inside the same transaction. */
data class StoredClipEvent(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
)

/** A remote clip event body received from a peer, already protocol-validated. */
data class RemoteClipEvent(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    /** [ClipKinds.TEXT] or [ClipKinds.IMAGE]; image events carry [media] and empty [content]. */
    val kind: String = ClipKinds.TEXT,
    /** Blob metadata for image events; the blob itself is already committed on disk. */
    val media: ClipMediaRef? = null,
)

/** An origin-authoritative unavailable marker; advances cursors without content. */
data class RemoteTerminalMarker(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val reason: String,
) {
    init {
        require(reason in TerminalReasons.ALL) { "Unknown terminal reason: $reason" }
    }
}

sealed class RemoteStoreResult {
    /** The event committed; [receiveState] is the origin state after the transaction. */
    data class Stored(val receiveState: OriginReceiveState) : RemoteStoreResult()

    /** The identical event or marker was already persisted; success for idempotent retries. */
    data object AlreadyPersisted : RemoteStoreResult()

    /** The idempotency key maps to a different identity; the protocol treats this as fatal. */
    data class IdentityConflict(val detail: String) : RemoteStoreResult()
}

data class HistoryQuery(
    val searchText: String? = null,
    val limit: Int = 2_000,
    val offset: Int = 0,
) {
    init {
        require(limit in 1..MAXIMUM_LIMIT) { "Limit must be between 1 and $MAXIMUM_LIMIT." }
        require(offset >= 0) { "Offset cannot be negative." }
    }

    companion object {
        const val MAXIMUM_LIMIT = 2_000
    }
}

data class ClipHistoryEntry(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val deletedAtMs: Long?,
    val appliedAtMs: Long?,
    val kind: String = ClipKinds.TEXT,
) {
    val isDeleted: Boolean get() = deletedAtMs != null
    val isApplied: Boolean get() = appliedAtMs != null
    val isImage: Boolean get() = kind == ClipKinds.IMAGE
}

/** A clips row projected for sync: either an available body or a terminal marker. */
data class SyncableClipEvent(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String?,
    val contentHash: String?,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val terminalReason: String?,
    val kind: String = ClipKinds.TEXT,
    /** Present on live image rows so announce headers can carry the blob metadata. */
    val media: ClipMediaRef? = null,
) {
    val isTerminal: Boolean get() = terminalReason != null
    val isImage: Boolean get() = kind == ClipKinds.IMAGE
}

/** One pending outbox obligation plus the event (or terminal marker) it must announce. */
data class OutboxBatchItem(
    val outboxId: Long,
    val peerId: String,
    val state: String,
    val attempts: Int,
    val event: SyncableClipEvent,
)

/** Acknowledged inclusive ranges for one origin, as carried by an `ack_ranges` message. */
data class OriginAckRanges(
    val originDeviceId: String,
    val ranges: List<SequenceRange>,
)

class RetentionPolicy(
    val maximumEntries: Int = 2_000,
    val maximumAgeMs: Long = DEFAULT_MAXIMUM_AGE_MS,
) {
    init {
        require(maximumEntries > 0) { "The history limit must be positive." }
        require(maximumAgeMs > 0) { "The retention period must be positive." }
    }

    companion object {
        const val DEFAULT_MAXIMUM_AGE_MS: Long = 30L * 24 * 60 * 60 * 1_000
    }
}

internal fun ClipEventEntity.toHistoryEntry(): ClipHistoryEntry = ClipHistoryEntry(
    eventId = eventId,
    originDeviceId = originDeviceId,
    originSeq = originSeq,
    content = content,
    contentHash = contentHash,
    sourceApp = sourceApp,
    createdAtMs = createdAtMs,
    expiresAtMs = expiresAtMs,
    deletedAtMs = deletedAtMs,
    appliedAtMs = appliedAtMs,
    kind = kind,
)

internal fun ClipEventEntity.toSyncable(media: ClipMediaRef? = null): SyncableClipEvent = SyncableClipEvent(
    eventId = eventId,
    originDeviceId = originDeviceId,
    originSeq = originSeq,
    content = if (terminalReason == null) content else null,
    contentHash = if (terminalReason == null) contentHash else null,
    sourceApp = if (terminalReason == null) sourceApp else null,
    createdAtMs = createdAtMs,
    expiresAtMs = expiresAtMs,
    terminalReason = terminalReason,
    kind = kind,
    media = if (terminalReason == null) media else null,
)
