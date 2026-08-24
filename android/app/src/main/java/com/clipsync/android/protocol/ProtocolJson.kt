package com.clipsync.android.protocol

import java.security.MessageDigest
import java.util.Base64
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

    /**
     * Strict envelope + body validation for one session protocol version. v1 callers keep the
     * old single-argument shape; a v2 session passes [PROTOCOL_V2] and additionally accepts the
     * image chunk message types, image clip headers, and the required `hello.capabilities`.
     */
    fun parseEnvelope(source: String, version: Int = PROTOCOL_V1): ProtocolEnvelope {
        require(version == PROTOCOL_V1 || version == PROTOCOL_V2) { "Unknown protocol version: $version" }
        require(source.isNotBlank()) { "Protocol envelope must not be blank." }
        val element = json.parseToJsonElement(source)
        rejectExcessiveDepth(element.toString())
        val envelope = json.decodeFromJsonElement<ProtocolEnvelope>(element)

        if (envelope.version != version) {
            throw SerializationException("Unsupported protocol version: ${envelope.version}")
        }
        val types = if (version == PROTOCOL_V2) MESSAGE_TYPES_V2 else MESSAGE_TYPES
        if (envelope.type !in types) {
            throw SerializationException("Unsupported message type: ${envelope.type}")
        }
        val requestId = runCatching { UUID.fromString(envelope.requestId) }
            .getOrElse { throw SerializationException("request_id must be a UUID.", it) }
        if (!UUID_PATTERN.matches(envelope.requestId) || requestId == ZERO_UUID) {
            throw SerializationException("request_id must not be the zero UUID.")
        }
        validateBody(envelope.type, envelope.body, version)
        return envelope
    }

    private fun validateBody(type: String, body: kotlinx.serialization.json.JsonObject, version: Int) {
        when (type) {
            "hello" -> {
                if (version == PROTOCOL_V2) {
                    body.requireKeys(
                        "device_id", "platform", "client_version", "trust_epoch", "capabilities", "known_vector",
                    )
                    validateCapabilities(body.array("capabilities"))
                } else {
                    body.requireKeys("device_id", "platform", "client_version", "trust_epoch", "known_vector")
                }
                validateUuid(body.string("device_id"))
                body.string("platform").requireOneOf("windows", "android")
                body.long("trust_epoch").requireAtLeast(1)
                validateSyncState(body.objectValue("known_vector"))
            }
            "challenge" -> body.requireKeys(
                "algorithm", "nonce", "challenger_device_id", "responder_device_id", "trust_epoch", "expires_at_ms",
            )
            "auth" -> body.requireKeys(
                "algorithm", "challenge_request_id", "responder_device_id", "trust_epoch", "proof",
            )
            "known_vector" -> validateSyncState(body)
            "want_ranges" -> validateOriginRanges(body, "requests")
            "ack_ranges" -> validateOriginRanges(body, "acks")
            "clip_announce" -> validateClipHeaders(body, version)
            "clip_fetch" -> {
                body.requireKeys("event_ids")
                val ids = body.array("event_ids").map { it.stringValue() }
                requireProtocol(ids.isNotEmpty() && ids.size <= 128 && ids.distinct().size == ids.size)
                ids.forEach(::validateUuid)
            }
            "clip_payload" -> validateClipPayload(body)
            "clip_payload_begin" -> validatePayloadBegin(body)
            "clip_payload_chunk" -> validatePayloadChunk(body)
            "clip_payload_end" -> {
                body.requireKeys("transfer_id", "event_id", "content_hash")
                validateUuid(body.string("transfer_id"))
                validateUuid(body.string("event_id"))
                validateContentHash(body.string("content_hash"))
            }
        }
    }

    private fun validateCapabilities(capabilities: JsonArray) {
        requireProtocol(capabilities.size <= MAX_CAPABILITIES)
        val tokens = capabilities.map { it.stringValue() }
        requireProtocol(tokens.distinct().size == tokens.size)
        tokens.forEach { requireProtocol(it in KNOWN_CAPABILITIES) }
    }

    private fun validatePayloadBegin(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("transfer_id", "event_id", "chunk_count", "encoded_bytes", "content_hash", "mime_type")
        validateUuid(body.string("transfer_id"))
        validateUuid(body.string("event_id"))
        val chunkCount = body.long("chunk_count")
        requireProtocol(chunkCount in 1..MAX_CHUNK_COUNT)
        val encodedBytes = body.long("encoded_bytes")
        requireProtocol(encodedBytes in 1..MAX_IMAGE_BYTES)
        validateContentHash(body.string("content_hash"))
        body.string("mime_type").requireOneOf("image/png", "image/jpeg")
    }

    private fun validatePayloadChunk(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("transfer_id", "event_id", "chunk_index", "chunk_count", "chunk_bytes", "data")
        validateUuid(body.string("transfer_id"))
        validateUuid(body.string("event_id"))
        val chunkCount = body.long("chunk_count")
        requireProtocol(chunkCount in 1..MAX_CHUNK_COUNT)
        val chunkIndex = body.long("chunk_index")
        requireProtocol(chunkIndex in 0 until chunkCount)
        val chunkBytes = body.long("chunk_bytes")
        requireProtocol(chunkBytes in 1..MAX_CHUNK_BYTES)
        val data = body.string("data")
        requireProtocol(BASE64URL_PATTERN.matches(data))
        val decoded = runCatching { Base64.getUrlDecoder().decode(data) }
            .getOrElse { throw SerializationException("Chunk data is not valid base64url.", it) }
        requireProtocol(decoded.size.toLong() == chunkBytes)
    }

    private fun validateSyncState(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("origins")
        val origins = body.array("origins")
        requireProtocol(origins.size <= 128)
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
        requireProtocol(requests.isNotEmpty() && requests.size <= 128)
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
        requireProtocol(ranges.isNotEmpty() && ranges.size <= 256)
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

    private fun validateClipHeaders(body: kotlinx.serialization.json.JsonObject, version: Int) {
        body.requireKeys("clips")
        val clips = body.array("clips")
        requireProtocol(clips.isNotEmpty() && clips.size <= 256)
        clips.forEach { element ->
            val clip = element.jsonObject
            when (clip.string("availability")) {
                "available" ->
                    if (version == PROTOCOL_V2 && clip["kind"]?.stringValue() == "image") {
                        validateImageHeader(clip)
                    } else {
                        clip.requireKeys(
                            "event_id", "origin_device_id", "origin_seq", "availability", "kind", "content_hash", "utf8_bytes", "created_at_ms",
                            optional = setOf("source_app", "expires_at_ms"),
                        )
                    }
                "unavailable" -> {
                    clip.requireKeys(
                        "event_id", "origin_device_id", "origin_seq", "availability", "reason",
                    )
                    if (version == PROTOCOL_V2) {
                        clip.string("reason").requireOneOf(
                            "local_only", "deleted", "expired", "policy_filtered", "not_found", "unsupported_media",
                        )
                    }
                }
                else -> throw SerializationException("Invalid clip availability.")
            }
        }
    }

    /** Protocol v2 section 4: an image header carries blob metadata and never `utf8_bytes`. */
    private fun validateImageHeader(clip: kotlinx.serialization.json.JsonObject) {
        clip.requireKeys(
            "event_id", "origin_device_id", "origin_seq", "availability", "kind", "mime_type",
            "content_hash", "encoded_bytes", "pixel_width", "pixel_height", "created_at_ms",
            optional = setOf("source_app", "expires_at_ms"),
        )
        clip.string("mime_type").requireOneOf("image/png", "image/jpeg")
        validateContentHash(clip.string("content_hash"))
        requireProtocol(clip.long("encoded_bytes") in 1..MAX_IMAGE_BYTES)
        val width = clip.long("pixel_width")
        val height = clip.long("pixel_height")
        requireProtocol(width in 1..MAX_PIXEL_SIDE && height in 1..MAX_PIXEL_SIDE)
        requireProtocol(width * height <= MAX_PIXELS)
    }

    private fun validateContentHash(value: String) {
        requireProtocol(CONTENT_HASH_PATTERN.matches(value))
    }

    private fun validateClipPayload(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("clips")
        val clips = body.array("clips")
        requireProtocol(clips.isNotEmpty() && clips.size <= 32)
        val eventIds = mutableSetOf<String>()
        val originSequences = mutableSetOf<Pair<String, Long>>()
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
            requireProtocol(utf8Bytes.isNotEmpty() && utf8Bytes.size <= MAX_PAYLOAD_BYTES)
            requireProtocol(clip.long("utf8_bytes") == utf8Bytes.size.toLong())
            requireProtocol(clip.string("content_hash") == sha256(utf8Bytes))
            val createdAt = clip.long("created_at_ms")
            clip["expires_at_ms"]?.jsonPrimitive?.longOrNullStrict()?.let { expiresAt ->
                requireProtocol(expiresAt > createdAt)
            }
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

    private fun requireProtocol(condition: Boolean) {
        if (!condition) throw SerializationException("Protocol schema constraint failed.")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun rejectExcessiveDepth(source: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in source) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_DEPTH) {
                        throw SerializationException("Protocol envelope exceeds maximum depth.")
                    }
                }
                '}', ']' -> depth -= 1
            }
        }
    }

    const val PROTOCOL_V1 = 1
    const val PROTOCOL_V2 = 2
    const val CAPABILITY_IMAGE_CLIP_V2 = "image_clip_v2"

    private const val MAX_DEPTH = 16
    private const val MAX_PAYLOAD_BYTES = 1_048_576
    private const val MAX_IMAGE_BYTES = 16_777_216L
    private const val MAX_PIXEL_SIDE = 8_192L
    private const val MAX_PIXELS = 33_554_432L
    private const val MAX_CHUNK_BYTES = 262_144L
    private const val MAX_CHUNK_COUNT = 64L
    private const val MAX_CAPABILITIES = 16
    private val KNOWN_CAPABILITIES = setOf(CAPABILITY_IMAGE_CLIP_V2)
    private val ZERO_UUID = UUID(0L, 0L)
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
    private val CONTENT_HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    private val BASE64URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    private val MESSAGE_TYPES = setOf(
        "hello",
        "challenge",
        "auth",
        "known_vector",
        "want_ranges",
        "clip_announce",
        "clip_fetch",
        "clip_payload",
        "ack_ranges",
        "error",
        "ping",
        "pong",
    )
    private val MESSAGE_TYPES_V2 = MESSAGE_TYPES + setOf(
        "clip_payload_begin",
        "clip_payload_chunk",
        "clip_payload_end",
    )
}
