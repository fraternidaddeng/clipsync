package com.clipsync.android.storage

import com.clipsync.android.media.ImageCodec
import com.clipsync.android.media.ImageCodecError
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.sync.SyncLimits
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A whole-file import/export failure with a stable error code and no clip content in the
 * message, so callers can surface it and log it safely. Mirrors the Windows
 * `HistoryTransferException`.
 */
class HistoryTransferException(val errorCode: String, message: String) : Exception(message)

object HistoryTransferErrorCodes {
    const val BAD_HEADER = "BAD_HEADER"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val MALFORMED_RECORD = "MALFORMED_RECORD"
    const val HASH_MISMATCH = "HASH_MISMATCH"
    const val COUNT_MISMATCH = "COUNT_MISMATCH"
    const val CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE"
}

/** Outcome counts of a merge import; conflicts leave the existing rows untouched. */
data class HistoryImportResult(
    val imported: Int,
    val skipped: Int,
    val conflicts: Int,
) {
    val total: Int get() = imported + skipped + conflicts
}

data class HistoryExportHeader(
    val formatVersion: Int,
    val exportedAtMs: Long,
    val exportingDeviceId: String,
    val platform: String,
    val eventCount: Int,
)

/**
 * Blob metadata of one live image record (docs/export-format-v2.md §4.2).
 * [encodedData] carries the decoded (or to-be-embedded) image bytes; null on a
 * metadata-only record.
 */
data class HistoryExportedMedia(
    val mimeType: String,
    val encodedBytes: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val encodedData: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = other is HistoryExportedMedia &&
        mimeType == other.mimeType &&
        encodedBytes == other.encodedBytes &&
        pixelWidth == other.pixelWidth &&
        pixelHeight == other.pixelHeight &&
        (encodedData?.contentEquals(other.encodedData ?: ByteArray(0)) ?: (other.encodedData == null))

    override fun hashCode(): Int = 31 * mimeType.hashCode() + encodedBytes
}

/** One validated clip record of an export file: a live body or a terminal tombstone. */
data class HistoryExportedClip(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val kind: String = ClipKinds.TEXT,
    val content: String?,
    val contentHash: String?,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val deletedAtMs: Long?,
    val terminalReason: String?,
    val media: HistoryExportedMedia? = null,
) {
    val isTerminal: Boolean get() = terminalReason != null
    val isImage: Boolean get() = kind == ClipKinds.IMAGE
}

/**
 * Line-level reader/writer for docs/export-format-v1.md and docs/export-format-v2.md
 * (JSON Lines: one header record, then one clip record per line). Parsing is strict —
 * unknown fields, missing fields, and wrong types reject the record — and every clip's
 * content hash (text bytes or embedded image bytes) is recomputed, so a tampered or
 * truncated file fails before the repository applies anything. Byte-compatible with the
 * Windows `HistoryExportFormat`.
 */
object HistoryExportFormat {
    /** The newest version this writer can produce and this reader accepts. */
    const val FORMAT_VERSION = 2

    /** The frozen text-only version, still written when nothing requires v2. */
    const val TEXT_ONLY_FORMAT_VERSION = 1

    const val FORMAT = "clipsync-history"
    const val SUGGESTED_EXTENSION = ".jsonl"

    /** Embedded image bytes cap: the same 16 MiB the protocol and storage enforce. */
    const val MAX_EMBEDDED_MEDIA_BYTES = MediaLimits.MAX_ENCODED_BYTES

    private val terminalReasonsV1 = setOf(
        TerminalReasons.LOCAL_ONLY,
        TerminalReasons.DELETED,
        TerminalReasons.EXPIRED,
        TerminalReasons.POLICY_FILTERED,
        TerminalReasons.NOT_FOUND,
    )

    private val terminalReasonsV2 = terminalReasonsV1 + TerminalReasons.UNSUPPORTED_MEDIA

    private val canonicalUuid =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val lowercaseSha256 = Regex("^[0-9a-f]{64}$")

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        explicitNulls = true
    }

    fun writeHeaderLine(header: HistoryExportHeader): String {
        require(header.formatVersion == TEXT_ONLY_FORMAT_VERSION || header.formatVersion == FORMAT_VERSION) {
            "Unknown export format version."
        }
        return json.encodeToString(
            HeaderDto.serializer(),
            HeaderDto(
                type = "header",
                format = FORMAT,
                formatVersion = header.formatVersion,
                exportedAtMs = header.exportedAtMs,
                exportingDeviceId = header.exportingDeviceId,
                platform = header.platform,
                eventCount = header.eventCount,
            ),
        )
    }

    fun writeClipLine(clip: HistoryExportedClip): String {
        require(clip.kind == ClipKinds.TEXT || clip.kind == ClipKinds.IMAGE) { "Unknown clip kind." }
        require((clip.media?.encodedData?.size ?: 0) <= MAX_EMBEDDED_MEDIA_BYTES) {
            "Embedded image bytes exceed the 16 MiB cap."
        }
        return json.encodeToString(
            ClipDto.serializer(),
            ClipDto(
                type = "clip",
                eventId = clip.eventId,
                originDeviceId = clip.originDeviceId,
                originSeq = clip.originSeq,
                kind = clip.kind,
                content = clip.content,
                contentHash = clip.contentHash,
                sourceApp = clip.sourceApp,
                createdAtMs = clip.createdAtMs,
                expiresAtMs = clip.expiresAtMs,
                deletedAtMs = clip.deletedAtMs,
                terminalReason = clip.terminalReason,
                media = clip.media?.let { blob ->
                    MediaDto(
                        mimeType = blob.mimeType,
                        encodedBytes = blob.encodedBytes,
                        pixelWidth = blob.pixelWidth,
                        pixelHeight = blob.pixelHeight,
                        dataBase64 = blob.encodedData?.let { Base64.getEncoder().encodeToString(it) },
                    )
                },
            ),
        )
    }

    fun parseHeaderLine(line: String): HistoryExportHeader {
        val dto = decode(HeaderDto.serializer(), line, HistoryTransferErrorCodes.BAD_HEADER, lineNumber = 1)
        if (dto.type != "header" || dto.format != FORMAT) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.BAD_HEADER,
                "The file does not start with a clipsync-history header record.",
            )
        }
        if (dto.formatVersion != TEXT_ONLY_FORMAT_VERSION && dto.formatVersion != FORMAT_VERSION) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.UNSUPPORTED_VERSION,
                "Export format version ${dto.formatVersion} is not supported " +
                    "(expected $TEXT_ONLY_FORMAT_VERSION or $FORMAT_VERSION).",
            )
        }
        if (dto.eventCount < 0 || dto.exportedAtMs < 0 || dto.exportingDeviceId.isBlank()) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.BAD_HEADER,
                "The header record carries out-of-range values.",
            )
        }
        return HistoryExportHeader(
            dto.formatVersion,
            dto.exportedAtMs,
            dto.exportingDeviceId,
            dto.platform,
            dto.eventCount,
        )
    }

    fun parseClipLine(line: String, lineNumber: Int, formatVersion: Int = TEXT_ONLY_FORMAT_VERSION): HistoryExportedClip {
        if (formatVersion != TEXT_ONLY_FORMAT_VERSION && formatVersion != FORMAT_VERSION) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.UNSUPPORTED_VERSION,
                "Export format version $formatVersion is not supported.",
            )
        }
        val dto = decode(ClipDto.serializer(), line, HistoryTransferErrorCodes.MALFORMED_RECORD, lineNumber)
        val kindAllowed = dto.kind == ClipKinds.TEXT ||
            (formatVersion >= FORMAT_VERSION && dto.kind == ClipKinds.IMAGE)
        if (dto.type != "clip" || !kindAllowed) {
            throw malformed(lineNumber, "record type or kind is not supported")
        }
        if (!canonicalUuid.matches(dto.eventId)) {
            throw malformed(lineNumber, "event_id is not a canonical UUID")
        }
        if (dto.originDeviceId.isBlank() || dto.originDeviceId.length > 128) {
            throw malformed(lineNumber, "origin_device_id is missing or too long")
        }
        if (dto.originSeq < 1) {
            throw malformed(lineNumber, "origin_seq must be at least 1")
        }

        val allowedReasons = if (formatVersion >= FORMAT_VERSION) terminalReasonsV2 else terminalReasonsV1
        var media: HistoryExportedMedia? = null
        if (dto.terminalReason == null) {
            media = if (dto.kind == ClipKinds.IMAGE) {
                validateLiveImage(dto, lineNumber)
            } else {
                validateLiveText(dto, lineNumber)
                null
            }
        } else {
            if (dto.terminalReason !in allowedReasons) {
                throw malformed(lineNumber, "terminal_reason is not a known value")
            }
            if (dto.content != null || dto.contentHash != null || dto.sourceApp != null ||
                dto.deletedAtMs == null || dto.media != null
            ) {
                throw malformed(lineNumber, "a terminal record must carry no content and a deleted_at_ms")
            }
        }

        return HistoryExportedClip(
            eventId = dto.eventId.lowercase(),
            originDeviceId = dto.originDeviceId,
            originSeq = dto.originSeq,
            kind = dto.kind,
            content = dto.content,
            contentHash = dto.contentHash,
            sourceApp = dto.sourceApp,
            createdAtMs = dto.createdAtMs,
            expiresAtMs = dto.expiresAtMs,
            deletedAtMs = dto.deletedAtMs,
            terminalReason = dto.terminalReason,
            media = media,
        )
    }

    private fun validateLiveText(dto: ClipDto, lineNumber: Int) {
        if (dto.content == null || dto.contentHash == null || dto.deletedAtMs != null || dto.media != null) {
            throw malformed(lineNumber, "a live record needs content and hash and no deleted_at_ms")
        }
        if (dto.content.toByteArray(Charsets.UTF_8).size > SyncLimits.MAX_CONTENT_UTF8_BYTES) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.CONTENT_TOO_LARGE,
                "Line $lineNumber: content exceeds ${SyncLimits.MAX_CONTENT_UTF8_BYTES} UTF-8 bytes.",
            )
        }
        if (sha256Hex(dto.content.toByteArray(Charsets.UTF_8)) != dto.contentHash) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.HASH_MISMATCH,
                "Line $lineNumber: content_hash does not match the content.",
            )
        }
    }

    private fun validateLiveImage(dto: ClipDto, lineNumber: Int): HistoryExportedMedia {
        if (dto.content != null || dto.deletedAtMs != null || dto.media == null) {
            throw malformed(lineNumber, "a live image record needs media and no content or deleted_at_ms")
        }
        if (dto.contentHash == null || !lowercaseSha256.matches(dto.contentHash)) {
            throw malformed(lineNumber, "an image content_hash must be 64 lowercase hex characters")
        }
        val declared = dto.media
        if (!MediaLimits.isSupportedMime(declared.mimeType)) {
            throw malformed(lineNumber, "media mime_type is not supported")
        }
        if (declared.encodedBytes < 1 || declared.encodedBytes > MAX_EMBEDDED_MEDIA_BYTES) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.CONTENT_TOO_LARGE,
                "Line $lineNumber: media encoded_bytes is out of the 1..$MAX_EMBEDDED_MEDIA_BYTES range.",
            )
        }
        if (!MediaLimits.fitsPixelBudget(declared.pixelWidth, declared.pixelHeight)) {
            throw malformed(lineNumber, "media dimensions exceed the pixel budget")
        }

        var decoded: ByteArray? = null
        if (declared.dataBase64 != null) {
            decoded = try {
                Base64.getDecoder().decode(declared.dataBase64)
            } catch (_: IllegalArgumentException) {
                throw malformed(lineNumber, "media data_base64 is not valid base64")
            }
            if (decoded.size > MAX_EMBEDDED_MEDIA_BYTES) {
                throw HistoryTransferException(
                    HistoryTransferErrorCodes.CONTENT_TOO_LARGE,
                    "Line $lineNumber: embedded image bytes exceed $MAX_EMBEDDED_MEDIA_BYTES bytes.",
                )
            }
            if (decoded.size != declared.encodedBytes) {
                throw malformed(lineNumber, "embedded image length does not match encoded_bytes")
            }
            if (sha256Hex(decoded) != dto.contentHash) {
                throw HistoryTransferException(
                    HistoryTransferErrorCodes.HASH_MISMATCH,
                    "Line $lineNumber: content_hash does not match the embedded image bytes.",
                )
            }
            val (error, image) = ImageCodec.tryInspect(decoded)
            if (error != ImageCodecError.OK || image == null ||
                image.mimeType != declared.mimeType ||
                image.pixelWidth != declared.pixelWidth ||
                image.pixelHeight != declared.pixelHeight
            ) {
                throw malformed(lineNumber, "embedded image bytes do not match the declared media metadata")
            }
        }

        return HistoryExportedMedia(
            mimeType = declared.mimeType,
            encodedBytes = declared.encodedBytes,
            pixelWidth = declared.pixelWidth,
            pixelHeight = declared.pixelHeight,
            encodedData = decoded,
        )
    }

    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        line: String,
        errorCode: String,
        lineNumber: Int,
    ): T = try {
        json.decodeFromString(serializer, line)
    } catch (_: SerializationException) {
        throw HistoryTransferException(errorCode, "Line $lineNumber: record is not valid JSON for this format.")
    } catch (_: IllegalArgumentException) {
        throw HistoryTransferException(errorCode, "Line $lineNumber: record is not valid JSON for this format.")
    }

    private fun malformed(lineNumber: Int, detail: String): HistoryTransferException =
        HistoryTransferException(HistoryTransferErrorCodes.MALFORMED_RECORD, "Line $lineNumber: $detail.")

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    @Serializable
    private data class HeaderDto(
        val type: String,
        val format: String,
        @SerialName("format_version") val formatVersion: Int,
        @SerialName("exported_at_ms") val exportedAtMs: Long,
        @SerialName("exporting_device_id") val exportingDeviceId: String,
        val platform: String,
        @SerialName("event_count") val eventCount: Int,
    )

    @Serializable
    private data class ClipDto(
        val type: String,
        @SerialName("event_id") val eventId: String,
        @SerialName("origin_device_id") val originDeviceId: String,
        @SerialName("origin_seq") val originSeq: Long,
        val kind: String,
        val content: String?,
        @SerialName("content_hash") val contentHash: String?,
        @SerialName("source_app") val sourceApp: String?,
        @SerialName("created_at_ms") val createdAtMs: Long,
        @SerialName("expires_at_ms") val expiresAtMs: Long?,
        @SerialName("deleted_at_ms") val deletedAtMs: Long?,
        @SerialName("terminal_reason") val terminalReason: String?,
        // Optional so v1 lines (which never carry it) still parse; omitted when null
        // (defaults are not encoded) so v1 output stays byte-compatible with pre-v2 writers.
        val media: MediaDto? = null,
    )

    @Serializable
    private data class MediaDto(
        @SerialName("mime_type") val mimeType: String,
        @SerialName("encoded_bytes") val encodedBytes: Int,
        @SerialName("pixel_width") val pixelWidth: Int,
        @SerialName("pixel_height") val pixelHeight: Int,
        @SerialName("data_base64") val dataBase64: String? = null,
    )
}
