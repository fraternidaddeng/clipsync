package com.clipsync.android.storage

import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
            kind = if (row.isImage) CLIP_KIND_IMAGE else CLIP_KIND_TEXT,
            content = if (row.isImage) null else row.content,
            contentHash = row.contentHash,
            sourceApp = row.sourceApp,
            createdAt = row.createdAtMs,
            expiresAt = row.expiresAtMs,
            deletedAt = null,
            terminalReason = null,
        )

    fun toMediaBlob(row: ClipEntry): MediaBlobEntity? {
        if (!row.isImage) {
            return null
        }
        val mime = row.mimeType ?: return null
        val encoded = row.encodedBytes ?: return null
        val width = row.pixelWidth ?: return null
        val height = row.pixelHeight ?: return null
        return MediaBlobEntity(
            contentHash = row.contentHash,
            mimeType = mime,
            encodedBytes = encoded,
            pixelWidth = width,
            pixelHeight = height,
            state = CLIP_MEDIA_READY,
            createdAt = row.createdAtMs,
        )
    }

    fun resolveMediaFile(row: ClipEntry, mediaDirectory: File?): File? {
        if (!row.isImage || mediaDirectory == null) {
            return null
        }
        val name = row.contentHash
        if (name.length != 64 || name.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return null
        }
        val candidate = File(mediaDirectory, name).canonicalFile
        val root = mediaDirectory.canonicalFile
        if (!candidate.path.startsWith(root.path + File.separator) && candidate.path != root.path) {
            return null
        }
        return candidate.takeIf { it.isFile }
    }

    fun commitImportedImage(media: MediaBlobStore, row: ClipEntry, file: File): Boolean {
        return try {
            val bytes = file.readBytes()
            val validated = media.commitBytes(bytes, row.contentHash)
            validated.mimeType == (row.mimeType ?: validated.mimeType) &&
                validated.encodedBytes == (row.encodedBytes ?: validated.encodedBytes) &&
                MediaLimits.fitsPixelBudget(validated.pixelWidth, validated.pixelHeight)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseRow(line: String): ClipEntry {
        val element = json.parseToJsonElement(line)
        val obj = element as? JsonObject
        require(obj != null)
        val eventId = obj.requiredString("event_id")
        val originDeviceId = obj.requiredString("origin_device_id")
        val originSeq = obj.requiredLong("origin_seq")
        val kind = obj.requiredString("kind")
        val contentHash = obj.requiredString("content_hash")
        val sourceApp = obj.optionalString("source_app")
        val createdAt = obj.requiredLong("created_at")
        val expiresAt = obj.optionalLong("expires_at")
        require(eventId.isNotBlank())
        require(originDeviceId.isNotBlank())
        require(originSeq >= 1L)
        if (expiresAt != null) {
            require(expiresAt > createdAt)
        }
        return if (kind == CLIP_KIND_IMAGE) {
            val mime = obj.requiredString("mime_type")
            val encodedBytes = obj.requiredLong("encoded_bytes").toInt()
            val pixelWidth = obj.requiredLong("pixel_width").toInt()
            val pixelHeight = obj.requiredLong("pixel_height").toInt()
            require(MediaLimits.isSupportedMime(mime))
            require(encodedBytes in 1..MediaLimits.MAX_ENCODED_BYTES)
            require(MediaLimits.fitsPixelBudget(pixelWidth, pixelHeight))
            require(contentHash.length == 64)
            ClipEntry(
                eventId = eventId,
                originDeviceId = originDeviceId,
                originSeq = originSeq,
                content = "",
                contentHash = contentHash,
                sourceApp = sourceApp?.trim()?.takeIf { it.isNotEmpty() },
                createdAtMs = createdAt,
                expiresAtMs = expiresAt,
                kind = CLIP_KIND_IMAGE,
                mimeType = mime,
                encodedBytes = encodedBytes,
                pixelWidth = pixelWidth,
                pixelHeight = pixelHeight,
            )
        } else {
            require(kind == CLIP_KIND_TEXT)
            val content = obj.requiredString("content")
            require(content.isNotEmpty())
            val utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size
            require(utf8Bytes <= MAX_CLIP_UTF8_BYTES)
            require(contentHash == Sha256ContentHasher.hash(content))
            ClipEntry(
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
