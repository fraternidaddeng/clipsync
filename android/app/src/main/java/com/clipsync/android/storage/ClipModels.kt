package com.clipsync.android.storage

/** Public repository types used by the later WebSocket client and history UI. */

const val SETTING_PAIRED_PEER_ID = "paired_peer_id"
const val SETTING_CAPTURE_BLACKLIST_ENABLED = CapturePolicy.SETTING_BLACKLIST_ENABLED
const val SETTING_CAPTURE_BLACKLIST_EXTRA = CapturePolicy.SETTING_BLACKLIST_EXTRA
const val CLIP_KIND_TEXT = "text"
const val CLIP_KIND_IMAGE = "image"
const val CLIP_MEDIA_READY = "ready"
const val CLIP_MEDIA_PENDING = "pending"
const val CLIP_MEDIA_MISSING = "missing"
const val SETTING_IMAGE_SYNC_ENABLED = "image_sync_enabled"
const val SETTING_AUTO_APPLY_IMAGES = "auto_apply_images"
const val OUTBOX_PENDING = "pending"
const val OUTBOX_ANNOUNCED = "announced"
const val MAX_CLIP_UTF8_BYTES = 1_048_576
const val LOCAL_DEDUP_WINDOW_MS = 2_000L
const val MAX_SEARCH_LIMIT = 2_000

/** SQLite host-parameter cap is 999 on Android 10–13; stay well under it. */
const val SQLITE_SAFE_IN_CLAUSE = 900

object TerminalReasons {
    const val LOCAL_ONLY = "local_only"
    const val DELETED = "deleted"
    const val EXPIRED = "expired"
    const val POLICY_FILTERED = "policy_filtered"
    const val NOT_FOUND = "not_found"
    const val UNSUPPORTED_MEDIA = "unsupported_media"

    val ALL: Set<String> = setOf(LOCAL_ONLY, DELETED, EXPIRED, POLICY_FILTERED, NOT_FOUND, UNSUPPORTED_MEDIA)
}

enum class CaptureRejectReason {
    EMPTY_TEXT,
    TOO_LARGE,
    DUPLICATE,
    BLOCKED_SOURCE,
    POLICY_PAUSED,
    UNSUPPORTED_MEDIA,
    DECODE_FAILED,
}

sealed class CaptureResult {
    data class Stored(
        val eventId: String,
        val originSeq: Long,
        val contentHash: String,
        val kind: String = CLIP_KIND_TEXT,
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
    val content: String?,
    val contentHash: String,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long? = null,
    val kind: String = CLIP_KIND_TEXT,
    val mimeType: String? = null,
    val encodedBytes: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
) {
    val isImage: Boolean get() = kind == CLIP_KIND_IMAGE
}

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
    val kind: String = CLIP_KIND_TEXT,
    val mimeType: String? = null,
    val encodedBytes: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
) {
    val isImage: Boolean get() = kind == CLIP_KIND_IMAGE
}

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
    val kind: String = CLIP_KIND_TEXT,
    val mimeType: String? = null,
    val encodedBytes: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
) {
    val isTerminal: Boolean get() = terminalReason != null
    val isImage: Boolean get() = kind == CLIP_KIND_IMAGE
}
