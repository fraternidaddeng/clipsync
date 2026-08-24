package com.clipsync.android.sync

import com.clipsync.android.protocol.ProtocolEnvelope
import com.clipsync.android.protocol.ProtocolJson
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Message type constants for protocol v1; must match ProtocolMessageTypes on Windows. */
object SyncMessageTypes {
    const val HELLO = "hello"
    const val CHALLENGE = "challenge"
    const val AUTH = "auth"
    const val KNOWN_VECTOR = "known_vector"
    const val WANT_RANGES = "want_ranges"
    const val CLIP_ANNOUNCE = "clip_announce"
    const val CLIP_FETCH = "clip_fetch"
    const val CLIP_PAYLOAD = "clip_payload"
    const val ACK_RANGES = "ack_ranges"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"
}

/** Stable protocol error codes; must match ProtocolErrorCodes on Windows. */
object SyncErrorCodes {
    const val MALFORMED_JSON = "MALFORMED_JSON"
    const val SCHEMA_VIOLATION = "SCHEMA_VIOLATION"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val CHALLENGE_EXPIRED = "CHALLENGE_EXPIRED"
    const val REPLAY_DETECTED = "REPLAY_DETECTED"
    const val DEVICE_REVOKED = "DEVICE_REVOKED"
    const val TRUST_EPOCH_MISMATCH = "TRUST_EPOCH_MISMATCH"
    const val MESSAGE_OUT_OF_ORDER = "MESSAGE_OUT_OF_ORDER"
    const val EVENT_CONFLICT = "EVENT_CONFLICT"
    const val PAYLOAD_NOT_FOUND = "PAYLOAD_NOT_FOUND"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

/** Wire limits from protocol v1 section 7. */
object SyncLimits {
    const val MAX_ANNOUNCE_CLIPS = 256
    const val MAX_PAYLOAD_CLIPS = 32
    const val MAX_FETCH_EVENT_IDS = 128
    const val MAX_CONTENT_UTF8_BYTES = 1_048_576
    const val MAX_PAYLOAD_BATCH_CONTENT_BYTES = 1_048_576L
    const val MAX_WEBSOCKET_TEXT_MESSAGE_BYTES = 7 * 1_048_576

    /**
     * True when [text]'s UTF-8 encoding is longer than [maxBytes], counted without
     * materializing the encoding (a `toByteArray` of a frame-limit-sized message would
     * allocate megabytes just to be measured). Matches `String.toByteArray(UTF_8)` sizing,
     * including the 1-byte replacement character for unpaired surrogates, and exits as soon
     * as the running total passes the limit.
     */
    fun utf8BytesExceed(text: String, maxBytes: Int): Boolean {
        // Every UTF-16 unit encodes to at least one byte, so this fast path is exact.
        if (text.length > maxBytes) {
            return true
        }
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val character = text[index]
            bytes += when {
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                character.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                character.isSurrogate() -> 1 // unpaired: the encoder writes '?'
                else -> 3
            }
            if (bytes > maxBytes) {
                return true
            }
            index++
        }
        return false
    }
}

const val HMAC_ALGORITHM = "hmac-sha256"

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
data class SyncStateBody(
    @SerialName("origins") val origins: List<OriginStateDto>,
)

@Serializable
data class HelloBody(
    @SerialName("device_id") val deviceId: String,
    @SerialName("platform") val platform: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
    @SerialName("known_vector") val knownVector: SyncStateBody,
)

@Serializable
data class ChallengeBody(
    @SerialName("algorithm") val algorithm: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("challenger_device_id") val challengerDeviceId: String,
    @SerialName("responder_device_id") val responderDeviceId: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
)

@Serializable
data class AuthBody(
    @SerialName("algorithm") val algorithm: String,
    @SerialName("challenge_request_id") val challengeRequestId: String,
    @SerialName("responder_device_id") val responderDeviceId: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
    @SerialName("proof") val proof: String,
)

@Serializable
data class OriginRangesDto(
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("ranges") val ranges: List<RangeDto>,
)

@Serializable
data class WantRangesBody(
    @SerialName("requests") val requests: List<OriginRangesDto>,
)

object ClipAvailability {
    const val AVAILABLE = "available"
    const val UNAVAILABLE = "unavailable"
}

@Serializable
data class ClipHeaderDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("origin_seq") val originSeq: Long,
    @SerialName("availability") val availability: String,
    @SerialName("kind") val kind: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("utf8_bytes") val utf8Bytes: Long? = null,
    @SerialName("source_app") val sourceApp: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    @SerialName("reason") val reason: String? = null,
)

@Serializable
data class ClipAnnounceBody(
    @SerialName("clips") val clips: List<ClipHeaderDto>,
)

@Serializable
data class ClipFetchBody(
    @SerialName("event_ids") val eventIds: List<String>,
)

@Serializable
data class ClipPayloadItemDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("origin_seq") val originSeq: Long,
    @SerialName("kind") val kind: String,
    @SerialName("content") val content: String,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("utf8_bytes") val utf8Bytes: Long,
    @SerialName("source_app") val sourceApp: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
)

@Serializable
data class ClipPayloadBody(
    @SerialName("clips") val clips: List<ClipPayloadItemDto>,
)

@Serializable
data class AckRangesBody(
    @SerialName("acks") val acks: List<OriginRangesDto>,
)

@Serializable
data class ErrorBody(
    @SerialName("code") val code: String,
    @SerialName("retryable") val retryable: Boolean,
    @SerialName("failed_type") val failedType: String? = null,
    @SerialName("retry_after_ms") val retryAfterMs: Long? = null,
)

@Serializable
data class PingBody(
    @SerialName("sent_at_ms") val sentAtMs: Long,
)

@Serializable
data class PongBody(
    @SerialName("ping_sent_at_ms") val pingSentAtMs: Long,
    @SerialName("sent_at_ms") val sentAtMs: Long,
)

/** A parsed, schema-validated inbound message with its typed body. */
data class SyncMessage(val type: String, val requestId: String, val body: Any)

/**
 * Typed encode/decode on top of the strict envelope validation in [ProtocolJson].
 * Encoding omits null optional fields per protocol section 2 (never `null` on the wire).
 */
object SyncWire {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    fun newRequestId(): String = UUID.randomUUID().toString()

    fun encode(type: String, requestId: String, body: Any): String {
        val bodyObject = when (body) {
            is HelloBody -> json.encodeToJsonElement(body)
            is ChallengeBody -> json.encodeToJsonElement(body)
            is AuthBody -> json.encodeToJsonElement(body)
            is SyncStateBody -> json.encodeToJsonElement(body)
            is WantRangesBody -> json.encodeToJsonElement(body)
            is ClipAnnounceBody -> json.encodeToJsonElement(body)
            is ClipFetchBody -> json.encodeToJsonElement(body)
            is ClipPayloadBody -> json.encodeToJsonElement(body)
            is AckRangesBody -> json.encodeToJsonElement(body)
            is ErrorBody -> json.encodeToJsonElement(body)
            is PingBody -> json.encodeToJsonElement(body)
            is PongBody -> json.encodeToJsonElement(body)
            else -> throw IllegalArgumentException("Unsupported body type: ${body.javaClass.simpleName}")
        }.jsonObject
        return json.encodeToString(
            ProtocolEnvelope.serializer(),
            ProtocolEnvelope(version = 1, type = type, requestId = requestId, body = bodyObject),
        )
    }

    /**
     * Parses one inbound frame through the strict shared validator, then decodes the typed body.
     * Throws SerializationException or IllegalArgumentException for anything off-contract.
     */
    fun decode(frame: String): SyncMessage {
        val envelope = ProtocolJson.parseEnvelope(frame)
        return SyncMessage(envelope.type, envelope.requestId, decodeBody(envelope.type, envelope.body))
    }

    private fun decodeBody(type: String, body: JsonObject): Any = when (type) {
        SyncMessageTypes.HELLO -> json.decodeFromJsonElement(HelloBody.serializer(), body)
        SyncMessageTypes.CHALLENGE -> json.decodeFromJsonElement(ChallengeBody.serializer(), body)
        SyncMessageTypes.AUTH -> json.decodeFromJsonElement(AuthBody.serializer(), body)
        SyncMessageTypes.KNOWN_VECTOR -> json.decodeFromJsonElement(SyncStateBody.serializer(), body)
        SyncMessageTypes.WANT_RANGES -> json.decodeFromJsonElement(WantRangesBody.serializer(), body)
        SyncMessageTypes.CLIP_ANNOUNCE -> json.decodeFromJsonElement(ClipAnnounceBody.serializer(), body)
        SyncMessageTypes.CLIP_FETCH -> json.decodeFromJsonElement(ClipFetchBody.serializer(), body)
        SyncMessageTypes.CLIP_PAYLOAD -> json.decodeFromJsonElement(ClipPayloadBody.serializer(), body)
        SyncMessageTypes.ACK_RANGES -> json.decodeFromJsonElement(AckRangesBody.serializer(), body)
        SyncMessageTypes.ERROR -> json.decodeFromJsonElement(ErrorBody.serializer(), body)
        SyncMessageTypes.PING -> json.decodeFromJsonElement(PingBody.serializer(), body)
        SyncMessageTypes.PONG -> json.decodeFromJsonElement(PongBody.serializer(), body)
        else -> throw IllegalArgumentException("Unsupported message type: $type")
    }
}
