package com.clipsync.android.protocol

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProtocolJson {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false
        explicitNulls = true
    }

    fun parseEnvelope(source: String): ProtocolEnvelope {
        require(source.isNotBlank()) { "Protocol envelope must not be blank." }
        ProtocolStrictJson.scan(source)
        val element = json.parseToJsonElement(source)
        val envelope = json.decodeFromJsonElement<ProtocolEnvelope>(element)

        if (envelope.version != ProtocolLimits.PROTOCOL_VERSION) {
            throw ProtocolParseException(
                ProtocolErrorCodes.UNSUPPORTED_VERSION,
                "Unsupported protocol version: ${envelope.version}",
            )
        }
        if (envelope.type !in ProtocolMessageTypes.ALL) {
            throw ProtocolParseException(
                ProtocolErrorCodes.SCHEMA_VIOLATION,
                "Unsupported message type: ${envelope.type}",
            )
        }
        val requestId = runCatching { UUID.fromString(envelope.requestId) }
            .getOrElse {
                throw ProtocolParseException(ProtocolErrorCodes.SCHEMA_VIOLATION, "request_id must be a UUID.")
            }
        if (!UUID_PATTERN.matches(envelope.requestId) || requestId == ZERO_UUID) {
            throw ProtocolParseException(ProtocolErrorCodes.SCHEMA_VIOLATION, "request_id must not be the zero UUID.")
        }
        validateBody(envelope.type, envelope.body)
        return envelope
    }

    fun parseEnvelopeV2(source: String): ProtocolEnvelope {
        require(source.isNotBlank()) { "Protocol envelope must not be blank." }
        ProtocolStrictJson.scan(source)
        val element = json.parseToJsonElement(source)
        val envelope = json.decodeFromJsonElement<ProtocolEnvelope>(element)

        if (envelope.version != ProtocolLimits.PROTOCOL_VERSION_V2) {
            throw ProtocolParseException(
                ProtocolErrorCodes.UNSUPPORTED_VERSION,
                "Unsupported protocol version: ${envelope.version}",
            )
        }
        if (envelope.type !in ProtocolMessageTypes.ALL_V2) {
            throw ProtocolParseException(
                ProtocolErrorCodes.SCHEMA_VIOLATION,
                "Unsupported message type: ${envelope.type}",
            )
        }
        val requestId = runCatching { UUID.fromString(envelope.requestId) }
            .getOrElse {
                throw ProtocolParseException(ProtocolErrorCodes.SCHEMA_VIOLATION, "request_id must be a UUID.")
            }
        if (!UUID_PATTERN.matches(envelope.requestId) || requestId == ZERO_UUID) {
            throw ProtocolParseException(ProtocolErrorCodes.SCHEMA_VIOLATION, "request_id must not be the zero UUID.")
        }
        validateBodyV2(envelope.type, envelope.body)
        return envelope
    }

    private fun validateBody(type: String, body: kotlinx.serialization.json.JsonObject) {
        when (type) {
            "hello" -> {
                body.requireKeys("device_id", "platform", "client_version", "trust_epoch", "known_vector")
                validateUuid(body.string("device_id"))
                body.string("platform").requireOneOf("windows", "android")
                validateClientVersion(body.string("client_version"))
                body.long("trust_epoch").requireAtLeast(1)
                validateSyncState(body.objectValue("known_vector"))
            }
            "challenge" -> {
                body.requireKeys(
                    "algorithm", "nonce", "challenger_device_id", "responder_device_id", "trust_epoch", "expires_at_ms",
                )
                validateChallenge(body)
            }
            "auth" -> {
                body.requireKeys(
                    "algorithm", "challenge_request_id", "responder_device_id", "trust_epoch", "proof",
                )
                validateAuth(body)
            }
            "known_vector" -> validateSyncState(body)
            "want_ranges" -> validateOriginRanges(body, "requests")
            "ack_ranges" -> validateOriginRanges(body, "acks")
            "clip_announce" -> validateClipHeaders(body)
            "clip_fetch" -> {
                body.requireKeys("event_ids")
                val ids = body.array("event_ids").map { it.stringValue() }
                requireProtocol(
                    ids.isNotEmpty() &&
                        ids.size <= ProtocolLimits.MAX_FETCH_EVENT_IDS &&
                        ids.distinct().size == ids.size,
                )
                ids.forEach(::validateUuid)
            }
            "clip_payload" -> validateClipPayload(body)
            "error" -> validateError(body, v2 = false)
            "ping" -> body.requireKeys("sent_at_ms")
            "pong" -> body.requireKeys("ping_sent_at_ms", "sent_at_ms")
        }
    }

    private fun validateBodyV2(type: String, body: kotlinx.serialization.json.JsonObject) {
        when (type) {
            "hello" -> {
                body.requireKeys(
                    "device_id", "platform", "client_version", "trust_epoch", "known_vector", "capabilities",
                )
                validateUuid(body.string("device_id"))
                body.string("platform").requireOneOf("windows", "android")
                validateClientVersion(body.string("client_version"))
                body.long("trust_epoch").requireAtLeast(1)
                validateCapabilities(body.array("capabilities"))
                validateSyncState(body.objectValue("known_vector"))
            }
            "challenge" -> {
                body.requireKeys(
                    "algorithm", "nonce", "challenger_device_id", "responder_device_id", "trust_epoch", "expires_at_ms",
                )
                validateChallenge(body)
            }
            "auth" -> {
                body.requireKeys(
                    "algorithm", "challenge_request_id", "responder_device_id", "trust_epoch", "proof",
                )
                validateAuth(body)
            }
            "known_vector" -> validateSyncState(body)
            "want_ranges" -> validateOriginRanges(body, "requests")
            "ack_ranges" -> validateOriginRanges(body, "acks")
            "clip_announce" -> validateClipHeadersV2(body)
            "clip_fetch" -> {
                body.requireKeys("event_ids")
                val ids = body.array("event_ids").map { it.stringValue() }
                requireProtocol(
                    ids.isNotEmpty() &&
                        ids.size <= ProtocolLimits.MAX_FETCH_EVENT_IDS &&
                        ids.distinct().size == ids.size,
                )
                ids.forEach(::validateUuid)
            }
            "clip_payload" -> validateClipPayload(body)
            "clip_payload_begin" -> validatePayloadBegin(body)
            "clip_payload_chunk" -> validatePayloadChunk(body)
            "clip_payload_end" -> validatePayloadEnd(body)
            "error" -> validateError(body, v2 = true)
            "ping" -> body.requireKeys("sent_at_ms")
            "pong" -> body.requireKeys("ping_sent_at_ms", "sent_at_ms")
        }
    }

    private fun validateCapabilities(values: JsonArray) {
        requireProtocol(values.size in 1..ProtocolLimits.MAX_CAPABILITIES)
        val seen = mutableSetOf<String>()
        values.forEach { element ->
            val token = element.stringValue()
            requireProtocol(token == ProtocolLimits.CAPABILITY_IMAGE_CLIP_V2)
            requireProtocol(seen.add(token))
        }
    }

    private fun validateClipHeadersV2(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("clips")
        val clips = body.array("clips")
        requireProtocol(clips.isNotEmpty() && clips.size <= ProtocolLimits.MAX_ANNOUNCE_CLIPS)
        val eventIds = mutableSetOf<String>()
        val originSequences = mutableSetOf<Pair<String, Long>>()
        clips.forEach { element ->
            val clip = element.jsonObject
            val eventId = clip.string("event_id")
            val originId = clip.string("origin_device_id")
            val originSequence = clip.long("origin_seq")
            validateUuid(eventId)
            validateUuid(originId)
            requireProtocol(eventIds.add(eventId))
            requireProtocol(originSequences.add(originId to originSequence))
            requireProtocol(originSequence >= 1)
            when (clip.string("availability")) {
                "available" -> {
                    val kind = clip.string("kind")
                    if (kind == "text") {
                        clip.requireKeys(
                            "event_id", "origin_device_id", "origin_seq", "availability", "kind",
                            "content_hash", "utf8_bytes", "created_at_ms",
                            optional = setOf("source_app", "expires_at_ms"),
                        )
                        requireProtocol(isLowercaseSha256(clip.string("content_hash")))
                    } else {
                        requireProtocol(kind == "image")
                        clip.requireKeys(
                            "event_id", "origin_device_id", "origin_seq", "availability", "kind",
                            "content_hash", "mime_type", "encoded_bytes", "pixel_width", "pixel_height",
                            "created_at_ms",
                            optional = setOf("source_app", "expires_at_ms"),
                        )
                        requireProtocol(clip.string("mime_type") in setOf("image/png", "image/jpeg"))
                        requireProtocol(isLowercaseSha256(clip.string("content_hash")))
                        val encoded = clip.long("encoded_bytes")
                        requireProtocol(encoded in 1..ProtocolLimits.MAX_ENCODED_IMAGE_BYTES)
                        val width = clip.long("pixel_width")
                        val height = clip.long("pixel_height")
                        requireProtocol(width in 1..ProtocolLimits.MAX_IMAGE_SIDE)
                        requireProtocol(height in 1..ProtocolLimits.MAX_IMAGE_SIDE)
                        requireProtocol(width * height <= ProtocolLimits.MAX_IMAGE_PIXELS)
                    }
                }
                "unavailable" -> {
                    clip.requireKeys(
                        "event_id", "origin_device_id", "origin_seq", "availability", "reason",
                    )
                    requireProtocol(clip.string("reason") in ClipUnavailableReasons.ALL_V2)
                }
                else -> throw SerializationException("Invalid clip availability.")
            }
        }
    }

    private fun validatePayloadBegin(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys(
            "transfer_id", "event_id", "chunk_count", "encoded_bytes", "content_hash", "mime_type",
        )
        validateUuid(body.string("transfer_id"))
        validateUuid(body.string("event_id"))
        val chunkCount = body.long("chunk_count")
        val encodedBytes = body.long("encoded_bytes")
        requireProtocol(chunkCount in 1..ProtocolLimits.MAX_CHUNK_COUNT)
        requireProtocol(encodedBytes in 1..ProtocolLimits.MAX_ENCODED_IMAGE_BYTES)
        requireProtocol(body.string("mime_type") in setOf("image/png", "image/jpeg"))
        requireProtocol(isLowercaseSha256(body.string("content_hash")))
        val minChunks =
            (encodedBytes + ProtocolLimits.MAX_CHUNK_BYTES - 1) / ProtocolLimits.MAX_CHUNK_BYTES
        requireProtocol(chunkCount >= minChunks)
    }

    private fun validatePayloadChunk(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys(
            "transfer_id", "event_id", "chunk_index", "chunk_count", "chunk_bytes", "data",
        )
        validateUuid(body.string("transfer_id"))
        validateUuid(body.string("event_id"))
        val chunkCount = body.long("chunk_count")
        val chunkIndex = body.long("chunk_index")
        val chunkBytes = body.long("chunk_bytes")
        requireProtocol(chunkCount in 1..ProtocolLimits.MAX_CHUNK_COUNT)
        requireProtocol(chunkIndex in 0 until chunkCount)
        requireProtocol(chunkBytes in 1..ProtocolLimits.MAX_CHUNK_BYTES)
        val data = body.string("data")
        requireProtocol('+' !in data && '/' !in data && '=' !in data)
        val decoded = runCatching {
            java.util.Base64.getUrlDecoder().decode(data)
        }.getOrElse { throw SerializationException("chunk data is not unpadded base64url") }
        requireProtocol(decoded.size.toLong() == chunkBytes)
    }

    private fun validatePayloadEnd(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("transfer_id", "event_id", "content_hash")
        validateUuid(body.string("transfer_id"))
        validateUuid(body.string("event_id"))
        requireProtocol(isLowercaseSha256(body.string("content_hash")))
    }

    private fun validateSyncState(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("origins")
        val origins = body.array("origins")
        requireProtocol(origins.size <= ProtocolLimits.MAX_ORIGINS_PER_MESSAGE)
        val ids = mutableSetOf<String>()
        origins.forEach { element ->
            val origin = element.jsonObject
            origin.requireKeys("origin_device_id", "contiguous_seq", optional = setOf("received_ranges"))
            val id = origin.string("origin_device_id")
            validateUuid(id)
            requireProtocol(ids.add(id))
            val cursor = origin.long("contiguous_seq")
            requireProtocol(cursor >= 0)
            origin["received_ranges"]?.jsonArray?.let { validateRanges(it, cursor) }
        }
    }

    private fun validateOriginRanges(body: kotlinx.serialization.json.JsonObject, key: String) {
        body.requireKeys(key)
        val requests = body.array(key)
        requireProtocol(requests.isNotEmpty() && requests.size <= ProtocolLimits.MAX_ORIGINS_PER_MESSAGE)
        val originIds = mutableSetOf<String>()
        requests.forEach { element ->
            val request = element.jsonObject
            request.requireKeys("origin_device_id", "ranges")
            val originId = request.string("origin_device_id")
            validateUuid(originId)
            requireProtocol(originIds.add(originId))
            validateRanges(request.array("ranges"))
        }
    }

    private fun validateRanges(ranges: JsonArray, contiguousCursor: Long? = null) {
        requireProtocol(ranges.isNotEmpty() && ranges.size <= ProtocolLimits.MAX_RANGES_PER_ORIGIN)
        var previousEnd: Long? = null
        ranges.forEach { element ->
            val range = element.jsonObject
            range.requireKeys("start_seq", "end_seq")
            val start = range.long("start_seq")
            val end = range.long("end_seq")
            requireProtocol(start >= 1 && end >= start)
            contiguousCursor?.let { cursor -> requireProtocol(start > cursor + 1) }
            previousEnd?.let { priorEnd -> requireProtocol(start > priorEnd && start - priorEnd > 1) }
            previousEnd = end
        }
    }

    private fun validateClipHeaders(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("clips")
        val clips = body.array("clips")
        requireProtocol(clips.isNotEmpty() && clips.size <= ProtocolLimits.MAX_ANNOUNCE_CLIPS)
        val eventIds = mutableSetOf<String>()
        val originSequences = mutableSetOf<Pair<String, Long>>()
        clips.forEach { element ->
            val clip = element.jsonObject
            val eventId = clip.string("event_id")
            val originId = clip.string("origin_device_id")
            val originSequence = clip.long("origin_seq")
            validateUuid(eventId)
            validateUuid(originId)
            requireProtocol(eventIds.add(eventId))
            requireProtocol(originSequences.add(originId to originSequence))
            requireProtocol(originSequence >= 1)
            when (clip.string("availability")) {
                "available" -> {
                    clip.requireKeys(
                        "event_id", "origin_device_id", "origin_seq", "availability", "kind",
                        "content_hash", "utf8_bytes", "created_at_ms",
                        optional = setOf("source_app", "expires_at_ms"),
                    )
                    requireProtocol(clip.string("kind") == "text")
                    requireProtocol(isLowercaseSha256(clip.string("content_hash")))
                    val utf8 = clip.long("utf8_bytes")
                    requireProtocol(utf8 in 1..ProtocolLimits.MAX_CONTENT_UTF8_BYTES)
                    requireProtocol(clip.long("created_at_ms") >= 0)
                    validateOptionalMetadata(clip)
                }
                "unavailable" -> {
                    clip.requireKeys(
                        "event_id", "origin_device_id", "origin_seq", "availability", "reason",
                    )
                    requireProtocol(clip.string("reason") in ClipUnavailableReasons.ALL)
                }
                else -> throw SerializationException("Invalid clip availability.")
            }
        }
    }

    private fun validateClipPayload(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("clips")
        val clips = body.array("clips")
        requireProtocol(clips.isNotEmpty() && clips.size <= ProtocolLimits.MAX_PAYLOAD_CLIPS)
        val eventIds = mutableSetOf<String>()
        val originSequences = mutableSetOf<Pair<String, Long>>()
        var totalContentBytes = 0
        clips.forEach { element ->
            val clip = element.jsonObject
            clip.requireKeys(
                "event_id", "origin_device_id", "origin_seq", "kind", "content", "content_hash", "utf8_bytes", "created_at_ms",
                optional = setOf("source_app", "expires_at_ms"),
            )
            val eventId = clip.string("event_id")
            val originId = clip.string("origin_device_id")
            val originSequence = clip.long("origin_seq")
            validateUuid(eventId)
            validateUuid(originId)
            requireProtocol(eventIds.add(eventId))
            requireProtocol(originSequences.add(originId to originSequence))
            requireProtocol(originSequence >= 1)
            requireProtocol(clip.string("kind") == "text")
            val content = clip.string("content")
            val utf8Bytes = content.toByteArray(Charsets.UTF_8)
            requireProtocol(utf8Bytes.isNotEmpty() && utf8Bytes.size <= ProtocolLimits.MAX_CONTENT_UTF8_BYTES)
            requireProtocol(clip.long("utf8_bytes") == utf8Bytes.size.toLong())
            requireProtocol(isLowercaseSha256(clip.string("content_hash")))
            requireProtocol(clip.string("content_hash") == sha256(utf8Bytes))
            requireProtocol(clip.long("created_at_ms") >= 0)
            validateOptionalMetadata(clip)
            totalContentBytes += utf8Bytes.size
            requireProtocol(totalContentBytes <= ProtocolLimits.MAX_PAYLOAD_BATCH_CONTENT_BYTES)
        }
    }

    private fun kotlinx.serialization.json.JsonObject.requireKeys(
        vararg required: String,
        optional: Set<String> = emptySet(),
    ) {
        val requiredSet = required.toSet()
        requireProtocol(keys.containsAll(requiredSet))
        requireProtocol(keys.all { it in requiredSet || it in optional })
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        getValue(key).stringValue()

    private fun JsonElement.stringValue(): String {
        val primitive = runCatching { jsonPrimitive }
            .getOrElse { throw SerializationException("Expected string.", it) }
        if (!primitive.isString) {
            throw SerializationException("Expected string.")
        }
        return primitive.content
    }

    private fun kotlinx.serialization.json.JsonObject.long(key: String): Long =
        getValue(key).jsonPrimitive.longOrNullStrict()
            ?: throw SerializationException("Expected integer: $key")

    private fun JsonPrimitive.longOrNullStrict(): Long? =
        if (isString || content.contains('.') || content.contains('e', ignoreCase = true)) null else content.toLongOrNull()

    private fun kotlinx.serialization.json.JsonObject.array(key: String): JsonArray =
        runCatching { getValue(key).jsonArray }
            .getOrElse { throw SerializationException("Expected array: $key", it) }

    private fun kotlinx.serialization.json.JsonObject.objectValue(key: String) =
        runCatching { getValue(key).jsonObject }
            .getOrElse { throw SerializationException("Expected object: $key", it) }

    private fun validateUuid(value: String) {
        requireProtocol(UUID_PATTERN.matches(value))
        requireProtocol(UUID.fromString(value).toString() == value)
    }

    private fun String.requireOneOf(vararg values: String) {
        requireProtocol(this in values)
    }

    private fun Long.requireAtLeast(minimum: Long) {
        requireProtocol(this >= minimum)
    }

    private fun validateChallenge(body: kotlinx.serialization.json.JsonObject) {
        requireProtocol(body.string("algorithm") == "hmac-sha256")
        requireProtocol(isUnpaddedBase64Url256(body.string("nonce")))
        validateUuid(body.string("challenger_device_id"))
        validateUuid(body.string("responder_device_id"))
        body.long("trust_epoch").requireAtLeast(1)
        requireProtocol(body.long("expires_at_ms") >= 0)
    }

    private fun validateAuth(body: kotlinx.serialization.json.JsonObject) {
        requireProtocol(body.string("algorithm") == "hmac-sha256")
        validateUuid(body.string("challenge_request_id"))
        validateUuid(body.string("responder_device_id"))
        body.long("trust_epoch").requireAtLeast(1)
        requireProtocol(isUnpaddedBase64Url256(body.string("proof")))
    }

    private fun validateError(body: kotlinx.serialization.json.JsonObject, v2: Boolean) {
        body.requireKeys("code", "retryable", optional = setOf("failed_type", "retry_after_ms"))
        val codes = if (v2) ProtocolErrorCodes.ALL_V2 else ProtocolErrorCodes.ALL
        requireProtocol(body.string("code") in codes)
        body["failed_type"]?.let { failed ->
            val types = if (v2) ProtocolMessageTypes.ALL_V2 else ProtocolMessageTypes.ALL
            requireProtocol(failed.stringValue() in types)
        }
        body["retry_after_ms"]?.let {
            val retry = it.jsonPrimitive.longOrNullStrict()
                ?: throw ProtocolParseException(ProtocolErrorCodes.SCHEMA_VIOLATION, "retry_after_ms")
            requireProtocol(retry in 1..ProtocolLimits.MAX_RETRY_AFTER_MS)
        }
    }

    private fun validateClientVersion(value: String) {
        requireProtocol(value.length <= ProtocolLimits.MAX_CLIENT_VERSION_LENGTH)
        requireProtocol(CLIENT_VERSION.matches(value))
    }

    private fun validateOptionalMetadata(clip: kotlinx.serialization.json.JsonObject) {
        clip["source_app"]?.let { source ->
            val value = source.stringValue()
            requireProtocol(value.length in 1..ProtocolLimits.MAX_SOURCE_APP_LENGTH)
        }
        val createdAt = clip["created_at_ms"]?.jsonPrimitive?.longOrNullStrict()
        clip["expires_at_ms"]?.jsonPrimitive?.longOrNullStrict()?.let { expiresAt ->
            requireProtocol(expiresAt >= 0)
            if (createdAt != null) {
                requireProtocol(expiresAt > createdAt)
            }
        }
    }

    private fun isLowercaseSha256(value: String): Boolean = SHA256_HEX.matches(value)

    private fun isUnpaddedBase64Url256(value: String): Boolean {
        if (!BASE64_URL_256.matches(value)) {
            return false
        }
        return runCatching { java.util.Base64.getUrlDecoder().decode(value).size == 32 }.getOrDefault(false)
    }

    private fun requireProtocol(condition: Boolean) {
        if (!condition) {
            throw ProtocolParseException(ProtocolErrorCodes.SCHEMA_VIOLATION, "Protocol schema constraint failed.")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val ZERO_UUID = UUID(0L, 0L)
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val BASE64_URL_256 = Regex("^[A-Za-z0-9_-]{43}$")
    private val CLIENT_VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$")
}
