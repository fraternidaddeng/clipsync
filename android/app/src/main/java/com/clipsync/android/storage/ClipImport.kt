package com.clipsync.android.storage

import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets

/**
 * Decodes [ClipExport] JSON Lines for a **user-triggered** local history restore.
 *
 * Malformed, oversized, empty, or hash-mismatched lines yield `null` and must be
 * counted as skipped. Never log, print, or attach line text or clipboard bodies.
 *
 * Tombstone fields are not part of the current export format and are ignored;
 * every successfully decoded row is a live clip.
 */
object ClipImport {
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            isLenient = false
        }

    fun decodeLine(line: String): ClipEntry? =
        try {
            parseRow(line)
        } catch (_: Exception) {
            null
        }

    fun toLiveEntity(row: ClipEntry): ClipEntity =
        ClipEntity(
            eventId = row.eventId,
            originDeviceId = row.originDeviceId,
            originSeq = row.originSeq,
            kind = CLIP_KIND_TEXT,
            content = row.content,
            contentHash = row.contentHash,
            sourceApp = row.sourceApp,
            createdAt = row.createdAtMs,
            expiresAt = row.expiresAtMs,
            deletedAt = null,
            terminalReason = null,
        )

    private fun parseRow(line: String): ClipEntry {
        val element = json.parseToJsonElement(line)
        val obj = element as? JsonObject
        require(obj != null)
        val eventId = obj.requiredString("event_id")
        val originDeviceId = obj.requiredString("origin_device_id")
        val originSeq = obj.requiredLong("origin_seq")
        val kind = obj.requiredString("kind")
        val content = obj.requiredString("content")
        val contentHash = obj.requiredString("content_hash")
        val sourceApp = obj.optionalString("source_app")
        val createdAt = obj.requiredLong("created_at")
        val expiresAt = obj.optionalLong("expires_at")
        require(eventId.isNotBlank())
        require(originDeviceId.isNotBlank())
        require(originSeq >= 1L)
        require(kind == CLIP_KIND_TEXT)
        require(content.isNotEmpty())
        val utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size
        require(utf8Bytes <= MAX_CLIP_UTF8_BYTES)
        require(contentHash == Sha256ContentHasher.hash(content))
        if (expiresAt != null) {
            require(expiresAt > createdAt)
        }
        return ClipEntry(
            eventId = eventId,
            originDeviceId = originDeviceId,
            originSeq = originSeq,
            content = content,
            contentHash = contentHash,
            sourceApp = sourceApp?.trim()?.takeIf { it.isNotEmpty() },
            createdAtMs = createdAt,
            expiresAtMs = expiresAt,
        )
    }

    private fun JsonObject.requiredString(key: String): String {
        val element = this[key]
        require(element != null && element !is JsonNull)
        val primitive = element.jsonPrimitive
        require(primitive.isString)
        return primitive.content
    }

    private fun JsonObject.optionalString(key: String): String? {
        val element = this[key]
        if (element == null || element is JsonNull) {
            return null
        }
        val primitive = element.jsonPrimitive
        require(primitive.isString)
        return primitive.content
    }

    private fun JsonObject.requiredLong(key: String): Long {
        val element = this[key]
        require(element != null && element !is JsonNull)
        return element.jsonPrimitive.strictLong()
    }

    private fun JsonObject.optionalLong(key: String): Long? {
        val element = this[key]
        if (element == null || element is JsonNull) {
            return null
        }
        return element.jsonPrimitive.strictLong()
    }

    private fun JsonPrimitive.strictLong(): Long {
        require(!isString)
        require(!content.contains('.') && !content.contains('e', ignoreCase = true))
        return content.toLong()
    }
}

data class ClipImportCounts(
    val imported: Int,
    val skipped: Int,
)
