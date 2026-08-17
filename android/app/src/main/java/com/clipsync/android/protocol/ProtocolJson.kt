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
        val element = json.parseToJsonElement(source)
        rejectExcessiveDepth(element.toString())
        val envelope = json.decodeFromJsonElement<ProtocolEnvelope>(element)

        if (envelope.version != PROTOCOL_VERSION) {
            throw SerializationException("Unsupported protocol version: ${envelope.version}")
        }
        if (envelope.type !in MESSAGE_TYPES) {
            throw SerializationException("Unsupported message type: ${envelope.type}")
        }
        val requestId = runCatching { UUID.fromString(envelope.requestId) }
            .getOrElse { throw SerializationException("request_id must be a UUID.", it) }
        if (!UUID_PATTERN.matches(envelope.requestId) || requestId == ZERO_UUID) {
            throw SerializationException("request_id must not be the zero UUID.")
        }
        validateBody(envelope.type, envelope.body)
        return envelope
    }

    private fun validateBody(type: String, body: kotlinx.serialization.json.JsonObject) {
        when (type) {
            "hello" -> {
                body.requireKeys("device_id", "platform", "client_version", "trust_epoch", "known_vector")
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
            "clip_announce" -> validateClipHeaders(body)
            "clip_fetch" -> {
                body.requireKeys("event_ids")
                val ids = body.array("event_ids").map { it.stringValue() }
                requireProtocol(ids.isNotEmpty() && ids.size <= 128 && ids.distinct().size == ids.size)
                ids.forEach(::validateUuid)
            }
            "clip_payload" -> validateClipPayload(body)
            "error" -> body.requireKeys("code", "retryable", optional = setOf("failed_type", "retry_after_ms"))
            "ping" -> body.requireKeys("sent_at_ms")
            "pong" -> body.requireKeys("ping_sent_at_ms", "sent_at_ms")
        }
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

    private fun validateClipHeaders(body: kotlinx.serialization.json.JsonObject) {
        body.requireKeys("clips")
        val clips = body.array("clips")
        requireProtocol(clips.isNotEmpty() && clips.size <= 256)
        clips.forEach { element ->
            val clip = element.jsonObject
            when (clip.string("availability")) {
                "available" -> clip.requireKeys(
                    "event_id", "origin_device_id", "origin_seq", "availability", "kind", "content_hash", "utf8_bytes", "created_at_ms",
                    optional = setOf("source_app", "expires_at_ms"),
                )
                "unavailable" -> clip.requireKeys(
                    "event_id", "origin_device_id", "origin_seq", "availability", "reason",
                )
                else -> throw SerializationException("Invalid clip availability.")
            }
        }
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

    private const val PROTOCOL_VERSION = 1
    private const val MAX_DEPTH = 16
    private const val MAX_PAYLOAD_BYTES = 1_048_576
    private val ZERO_UUID = UUID(0L, 0L)
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
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
}
