package com.clipsync.android.storage

import com.clipsync.android.sync.SyncLimits
import java.security.MessageDigest
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
    val exportedAtMs: Long,
    val exportingDeviceId: String,
    val platform: String,
    val eventCount: Int,
)

/** One validated clip record of an export file: a live body or a terminal tombstone. */
data class HistoryExportedClip(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String?,
    val contentHash: String?,
    val sourceApp: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val deletedAtMs: Long?,
    val terminalReason: String?,
) {
    val isTerminal: Boolean get() = terminalReason != null
}

/**
 * Line-level reader/writer for docs/export-format-v1.md (JSON Lines: one header record,
 * then one clip record per line). Parsing is strict — unknown fields, missing fields, and
 * wrong types reject the record — and every clip's content hash is recomputed, so a
 * tampered or truncated file fails before the repository applies anything. Byte-compatible
 * with the Windows `HistoryExportFormat`.
 */
object HistoryExportFormat {
    const val FORMAT_VERSION = 1
    const val FORMAT = "clipsync-history"
    const val SUGGESTED_EXTENSION = ".jsonl"

    private val canonicalUuid =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        explicitNulls = true
    }

    fun writeHeaderLine(header: HistoryExportHeader): String = json.encodeToString(
        HeaderDto.serializer(),
        HeaderDto(
            type = "header",
            format = FORMAT,
            formatVersion = FORMAT_VERSION,
            exportedAtMs = header.exportedAtMs,
            exportingDeviceId = header.exportingDeviceId,
            platform = header.platform,
            eventCount = header.eventCount,
        ),
    )

    fun writeClipLine(clip: HistoryExportedClip): String = json.encodeToString(
        ClipDto.serializer(),
        ClipDto(
            type = "clip",
            eventId = clip.eventId,
            originDeviceId = clip.originDeviceId,
            originSeq = clip.originSeq,
            kind = "text",
            content = clip.content,
            contentHash = clip.contentHash,
            sourceApp = clip.sourceApp,
            createdAtMs = clip.createdAtMs,
            expiresAtMs = clip.expiresAtMs,
            deletedAtMs = clip.deletedAtMs,
            terminalReason = clip.terminalReason,
        ),
    )

    fun parseHeaderLine(line: String): HistoryExportHeader {
        val dto = decode(HeaderDto.serializer(), line, HistoryTransferErrorCodes.BAD_HEADER, lineNumber = 1)
        if (dto.type != "header" || dto.format != FORMAT) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.BAD_HEADER,
                "The file does not start with a clipsync-history header record.",
            )
        }
        if (dto.formatVersion != FORMAT_VERSION) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.UNSUPPORTED_VERSION,
                "Export format version ${dto.formatVersion} is not supported (expected $FORMAT_VERSION).",
            )
        }
        if (dto.eventCount < 0 || dto.exportedAtMs < 0 || dto.exportingDeviceId.isBlank()) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.BAD_HEADER,
                "The header record carries out-of-range values.",
            )
        }
        return HistoryExportHeader(dto.exportedAtMs, dto.exportingDeviceId, dto.platform, dto.eventCount)
    }

    fun parseClipLine(line: String, lineNumber: Int): HistoryExportedClip {
        val dto = decode(ClipDto.serializer(), line, HistoryTransferErrorCodes.MALFORMED_RECORD, lineNumber)
        if (dto.type != "clip" || dto.kind != "text") {
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

        if (dto.terminalReason == null) {
            if (dto.content == null || dto.contentHash == null || dto.deletedAtMs != null) {
                throw malformed(lineNumber, "a live record needs content and hash and no deleted_at_ms")
            }
            if (dto.content.toByteArray(Charsets.UTF_8).size > SyncLimits.MAX_CONTENT_UTF8_BYTES) {
                throw HistoryTransferException(
                    HistoryTransferErrorCodes.CONTENT_TOO_LARGE,
                    "Line $lineNumber: content exceeds ${SyncLimits.MAX_CONTENT_UTF8_BYTES} UTF-8 bytes.",
                )
            }
            if (sha256Hex(dto.content) != dto.contentHash) {
                throw HistoryTransferException(
                    HistoryTransferErrorCodes.HASH_MISMATCH,
                    "Line $lineNumber: content_hash does not match the content.",
                )
            }
        } else {
            if (dto.terminalReason !in TerminalReasons.ALL) {
                throw malformed(lineNumber, "terminal_reason is not a known value")
            }
            if (dto.content != null || dto.contentHash != null || dto.sourceApp != null || dto.deletedAtMs == null) {
                throw malformed(lineNumber, "a terminal record must carry no content and a deleted_at_ms")
            }
        }

        return HistoryExportedClip(
            eventId = dto.eventId.lowercase(),
            originDeviceId = dto.originDeviceId,
            originSeq = dto.originSeq,
            content = dto.content,
            contentHash = dto.contentHash,
            sourceApp = dto.sourceApp,
            createdAtMs = dto.createdAtMs,
            expiresAtMs = dto.expiresAtMs,
            deletedAtMs = dto.deletedAtMs,
            terminalReason = dto.terminalReason,
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

    private fun sha256Hex(text: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
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
    )
}
