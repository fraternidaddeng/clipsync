package com.clipsync.android.storage

/** Public repository types used by the later WebSocket client and history UI. */

const val SETTING_PAIRED_PEER_ID = "paired_peer_id"
const val CLIP_KIND_TEXT = "text"
const val OUTBOX_PENDING = "pending"
const val OUTBOX_ANNOUNCED = "announced"
const val MAX_CLIP_UTF8_BYTES = 1_048_576
const val LOCAL_DEDUP_WINDOW_MS = 2_000L
const val MAX_SEARCH_LIMIT = 2_000

object TerminalReasons {
    const val LOCAL_ONLY = "local_only"
    const val DELETED = "deleted"
    const val EXPIRED = "expired"
    const val POLICY_FILTERED = "policy_filtered"
    const val NOT_FOUND = "not_found"

    val ALL: Set<String> = setOf(LOCAL_ONLY, DELETED, EXPIRED, POLICY_FILTERED, NOT_FOUND)
}

enum class CaptureRejectReason {
    EMPTY_TEXT,
    TOO_LARGE,
    DUPLICATE,
}

sealed class CaptureResult {
    data class Stored(
        val eventId: String,
        val originSeq: Long,
        val contentHash: String,
    ) : CaptureResult()

    data class Rejected(val reason: CaptureRejectReason) : CaptureResult()
}

sealed class RemoteStoreResult {
    data class Stored(val receiveState: OriginReceiveState) : RemoteStoreResult()

    data object AlreadyPersisted : RemoteStoreResult()

    data class IdentityConflict(val detail: String) : RemoteStoreResult()
}

data class RemoteClipEvent(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long? = null,
)

data class RemoteTerminalMarker(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val reason: String,
)

data class KnownVector(
    val origins: Map<String, OriginReceiveState>,
)

data class OriginSequenceRanges(
    val originDeviceId: String,
    val ranges: List<SequenceRange>,
)

data class OutboxEntry(
    val id: Long,
    val peerId: String,
    val eventId: String,
    val state: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
)

data class ClipEntry(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
)

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
) {
    val isTerminal: Boolean get() = terminalReason != null
}
