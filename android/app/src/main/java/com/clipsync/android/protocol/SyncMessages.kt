package com.clipsync.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

object ProtocolMessageTypes {
    const val HELLO = "hello"
    const val CHALLENGE = "challenge"
    const val AUTH = "auth"
    const val KNOWN_VECTOR = "known_vector"
    const val WANT_RANGES = "want_ranges"
    const val CLIP_ANNOUNCE = "clip_announce"
    const val CLIP_FETCH = "clip_fetch"
    const val CLIP_PAYLOAD = "clip_payload"
    const val CLIP_PAYLOAD_BEGIN = "clip_payload_begin"
    const val CLIP_PAYLOAD_CHUNK = "clip_payload_chunk"
    const val CLIP_PAYLOAD_END = "clip_payload_end"
    const val ACK_RANGES = "ack_ranges"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"

    val ALL = setOf(
        HELLO, CHALLENGE, AUTH, KNOWN_VECTOR, WANT_RANGES, CLIP_ANNOUNCE,
        CLIP_FETCH, CLIP_PAYLOAD, ACK_RANGES, ERROR, PING, PONG,
    )

    val ALL_V2 = ALL + setOf(CLIP_PAYLOAD_BEGIN, CLIP_PAYLOAD_CHUNK, CLIP_PAYLOAD_END)
}

object ProtocolLimits {
    const val PROTOCOL_VERSION = 1
    const val PROTOCOL_VERSION_V2 = 2
    const val MAX_JSON_DEPTH = 16
    const val MAX_CONTENT_UTF8_BYTES = 1_048_576
    const val MAX_PAYLOAD_BATCH_CONTENT_BYTES = 1_048_576
    const val MAX_ANNOUNCE_CLIPS = 256
    const val MAX_PAYLOAD_CLIPS = 32
    const val MAX_FETCH_EVENT_IDS = 128
    const val MAX_ORIGINS_PER_MESSAGE = 128
    const val MAX_RANGES_PER_ORIGIN = 256
    const val MAX_WEBSOCKET_TEXT_MESSAGE_BYTES = 7 * 1_048_576
    const val MAX_SOURCE_APP_LENGTH = 256
    const val MAX_CLIENT_VERSION_LENGTH = 64
    const val MAX_RETRY_AFTER_MS = 300_000L
    const val MAX_CAPABILITIES = 16
    const val MAX_ENCODED_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 32 * 1024 * 1024
    const val MAX_IMAGE_SIDE = 8_192
    const val MAX_CHUNK_BYTES = 256 * 1024
    const val MAX_CHUNK_COUNT = 64
    const val MAX_CONCURRENT_IMAGE_DOWNLOADS = 2
    const val CAPABILITY_IMAGE_CLIP_V2 = "image_clip_v2"
}

object ProtocolErrorCodes {
    const val MALFORMED_JSON = "MALFORMED_JSON"
    const val SCHEMA_VIOLATION = "SCHEMA_VIOLATION"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val CHALLENGE_EXPIRED = "CHALLENGE_EXPIRED"
    const val REPLAY_DETECTED = "REPLAY_DETECTED"
    const val DEVICE_REVOKED = "DEVICE_REVOKED"
    const val TRUST_EPOCH_MISMATCH = "TRUST_EPOCH_MISMATCH"
    const val MESSAGE_OUT_OF_ORDER = "MESSAGE_OUT_OF_ORDER"
    const val INVALID_RANGE = "INVALID_RANGE"
    const val EVENT_CONFLICT = "EVENT_CONFLICT"
    const val PAYLOAD_NOT_FOUND = "PAYLOAD_NOT_FOUND"
    const val HASH_MISMATCH = "HASH_MISMATCH"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val UNSUPPORTED_MEDIA = "UNSUPPORTED_MEDIA"
    const val MEDIA_TOO_LARGE = "MEDIA_TOO_LARGE"
    const val MEDIA_DECODE_FAILED = "MEDIA_DECODE_FAILED"
    const val MEDIA_HASH_MISMATCH = "MEDIA_HASH_MISMATCH"
    const val MEDIA_OUT_OF_ORDER = "MEDIA_OUT_OF_ORDER"
    const val MEDIA_STORAGE_FAILED = "MEDIA_STORAGE_FAILED"

    val ALL = setOf(
        MALFORMED_JSON, SCHEMA_VIOLATION, UNSUPPORTED_VERSION, AUTH_REQUIRED, AUTH_FAILED,
        CHALLENGE_EXPIRED, REPLAY_DETECTED, DEVICE_REVOKED, TRUST_EPOCH_MISMATCH, MESSAGE_OUT_OF_ORDER,
        INVALID_RANGE, EVENT_CONFLICT, PAYLOAD_NOT_FOUND, HASH_MISMATCH, PAYLOAD_TOO_LARGE,
        RATE_LIMITED, INTERNAL_ERROR,
    )

    val ALL_V2 = ALL + setOf(
        UNSUPPORTED_MEDIA, MEDIA_TOO_LARGE, MEDIA_DECODE_FAILED, MEDIA_HASH_MISMATCH,
        MEDIA_OUT_OF_ORDER, MEDIA_STORAGE_FAILED,
    )
}

object ClipAvailability {
    const val AVAILABLE = "available"
    const val UNAVAILABLE = "unavailable"
}

object ClipUnavailableReasons {
    const val LOCAL_ONLY = "local_only"
    const val DELETED = "deleted"
    const val EXPIRED = "expired"
    const val POLICY_FILTERED = "policy_filtered"
    const val NOT_FOUND = "not_found"
    const val UNSUPPORTED_MEDIA = "unsupported_media"

    val ALL = setOf(LOCAL_ONLY, DELETED, EXPIRED, POLICY_FILTERED, NOT_FOUND)
    val ALL_V2 = ALL + setOf(UNSUPPORTED_MEDIA)
}

sealed interface SyncMessageBody

@Serializable
data class RangeDto(
    @SerialName("start_seq") val startSeq: Long,
    @SerialName("end_seq") val endSeq: Long,
)

@Serializable
data class OriginStateDto(
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("contiguous_seq") val contiguousSeq: Long,
    @SerialName("received_ranges") val receivedRanges: List<RangeDto>? = null,
)

@Serializable
data class SyncStateDto(
    val origins: List<OriginStateDto>,
) : SyncMessageBody

@Serializable
data class HelloBody(
    @SerialName("device_id") val deviceId: String,
    val platform: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
    @SerialName("known_vector") val knownVector: SyncStateDto,
    val capabilities: List<String>? = null,
) : SyncMessageBody

@Serializable
data class ChallengeBody(
    val algorithm: String,
    val nonce: String,
    @SerialName("challenger_device_id") val challengerDeviceId: String,
    @SerialName("responder_device_id") val responderDeviceId: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
) : SyncMessageBody

@Serializable
data class AuthBody(
    val algorithm: String,
    @SerialName("challenge_request_id") val challengeRequestId: String,
    @SerialName("responder_device_id") val responderDeviceId: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
    val proof: String,
) : SyncMessageBody

@Serializable
data class OriginRangesDto(
    @SerialName("origin_device_id") val originDeviceId: String,
    val ranges: List<RangeDto>,
)

@Serializable
data class WantRangesBody(
    val requests: List<OriginRangesDto>,
) : SyncMessageBody

@Serializable
data class ClipHeaderDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("origin_seq") val originSeq: Long,
    val availability: String,
    val kind: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("utf8_bytes") val utf8Bytes: Long? = null,
    @SerialName("source_app") val sourceApp: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    val reason: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("encoded_bytes") val encodedBytes: Long? = null,
    @SerialName("pixel_width") val pixelWidth: Long? = null,
    @SerialName("pixel_height") val pixelHeight: Long? = null,
)

@Serializable
data class ClipAnnounceBody(
    val clips: List<ClipHeaderDto>,
) : SyncMessageBody

@Serializable
data class ClipFetchBody(
    @SerialName("event_ids") val eventIds: List<String>,
) : SyncMessageBody

@Serializable
data class ClipPayloadItemDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("origin_seq") val originSeq: Long,
    val kind: String,
    val content: String,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("utf8_bytes") val utf8Bytes: Long,
    @SerialName("source_app") val sourceApp: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
)

@Serializable
data class ClipPayloadBody(
    val clips: List<ClipPayloadItemDto>,
) : SyncMessageBody

@Serializable
data class ClipPayloadBeginBody(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("chunk_count") val chunkCount: Long,
    @SerialName("encoded_bytes") val encodedBytes: Long,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("mime_type") val mimeType: String,
) : SyncMessageBody

@Serializable
data class ClipPayloadChunkBody(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("chunk_index") val chunkIndex: Long,
    @SerialName("chunk_count") val chunkCount: Long,
    @SerialName("chunk_bytes") val chunkBytes: Long,
    val data: String,
) : SyncMessageBody

@Serializable
data class ClipPayloadEndBody(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("content_hash") val contentHash: String,
) : SyncMessageBody

@Serializable
data class AckRangesBody(
    val acks: List<OriginRangesDto>,
) : SyncMessageBody

@Serializable
data class ErrorBody(
    val code: String,
    val retryable: Boolean,
    @SerialName("failed_type") val failedType: String? = null,
    @SerialName("retry_after_ms") val retryAfterMs: Long? = null,
) : SyncMessageBody

@Serializable
data class PingBody(
    @SerialName("sent_at_ms") val sentAtMs: Long,
) : SyncMessageBody

@Serializable
data class PongBody(
    @SerialName("ping_sent_at_ms") val pingSentAtMs: Long,
    @SerialName("sent_at_ms") val sentAtMs: Long,
) : SyncMessageBody

data class ParsedSyncMessage(
    val version: Int,
    val type: String,
    val requestId: String,
    val body: SyncMessageBody,
)

/**
 * Typed v1 bodies. Envelope JSON is validated by [ProtocolJson.parseEnvelope]; this
 * object only maps the already-accepted body object onto Kotlin types.
 */
object SyncMessages {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    fun parse(source: String): ParsedSyncMessage =
        parse(source, ProtocolLimits.PROTOCOL_VERSION)

    fun parse(source: String, version: Int): ParsedSyncMessage {
        val envelope = if (version == ProtocolLimits.PROTOCOL_VERSION_V2) {
            ProtocolJson.parseEnvelopeV2(source)
        } else {
            ProtocolJson.parseEnvelope(source)
        }
        return ParsedSyncMessage(
            version = envelope.version,
            type = envelope.type,
            requestId = envelope.requestId,
            body = decodeBody(envelope.type, envelope.body),
        )
    }

    fun decodeBody(type: String, body: JsonObject): SyncMessageBody = when (type) {
        ProtocolMessageTypes.HELLO -> json.decodeFromJsonElement<HelloBody>(body)
        ProtocolMessageTypes.CHALLENGE -> json.decodeFromJsonElement<ChallengeBody>(body)
        ProtocolMessageTypes.AUTH -> json.decodeFromJsonElement<AuthBody>(body)
        ProtocolMessageTypes.KNOWN_VECTOR -> json.decodeFromJsonElement<SyncStateDto>(body)
        ProtocolMessageTypes.WANT_RANGES -> json.decodeFromJsonElement<WantRangesBody>(body)
        ProtocolMessageTypes.CLIP_ANNOUNCE -> json.decodeFromJsonElement<ClipAnnounceBody>(body)
        ProtocolMessageTypes.CLIP_FETCH -> json.decodeFromJsonElement<ClipFetchBody>(body)
        ProtocolMessageTypes.CLIP_PAYLOAD -> json.decodeFromJsonElement<ClipPayloadBody>(body)
        ProtocolMessageTypes.CLIP_PAYLOAD_BEGIN -> json.decodeFromJsonElement<ClipPayloadBeginBody>(body)
        ProtocolMessageTypes.CLIP_PAYLOAD_CHUNK -> json.decodeFromJsonElement<ClipPayloadChunkBody>(body)
        ProtocolMessageTypes.CLIP_PAYLOAD_END -> json.decodeFromJsonElement<ClipPayloadEndBody>(body)
        ProtocolMessageTypes.ACK_RANGES -> json.decodeFromJsonElement<AckRangesBody>(body)
        ProtocolMessageTypes.ERROR -> json.decodeFromJsonElement<ErrorBody>(body)
        ProtocolMessageTypes.PING -> json.decodeFromJsonElement<PingBody>(body)
        ProtocolMessageTypes.PONG -> json.decodeFromJsonElement<PongBody>(body)
        else -> error("Unsupported message type: $type")
    }
}
