package com.clipsync.android.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Encodes clip rows as JSON Lines for a **user-triggered** local export.
 *
 * The returned string contains plaintext clipboard bodies. Call this only after
 * an explicit user action (for example a SAF picker). Never log, print, or
 * attach the result to diagnostics or crash reports.
 *
 * Field gap vs `docs/stage-6-migration-export.md`: [ClipEntry] (what
 * [ClipRepository.search] returns) does not expose `kind`, `deleted_at`, or
 * `terminal_reason`. This encoder writes `kind` as [CLIP_KIND_TEXT] and omits
 * the tombstone fields. `search()` also excludes soft-deleted rows, so a later
 * full-table export will need a dedicated read path.
 */
object ClipExport {
    val KEYS: List<String> = listOf(
        "event_id",
        "origin_device_id",
        "origin_seq",
        "kind",
        "content",
        "content_hash",
        "source_app",
        "created_at",
        "expires_at",
    )

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
    }

    fun encodeJsonLines(rows: List<ClipEntry>): String {
        if (rows.isEmpty()) {
            return ""
        }
        return buildString {
            for (row in rows) {
                append(encodeRow(row))
                append('\n')
            }
        }
    }

    private fun encodeRow(row: ClipEntry): String {
        val obj = buildJsonObject {
            put("event_id", row.eventId)
            put("origin_device_id", row.originDeviceId)
            put("origin_seq", row.originSeq)
            put("kind", CLIP_KIND_TEXT)
            put("content", row.content)
            put("content_hash", row.contentHash)
            if (row.sourceApp == null) {
                put("source_app", JsonNull)
            } else {
                put("source_app", row.sourceApp)
            }
            put("created_at", row.createdAtMs)
            val expiresAt = row.expiresAtMs
            if (expiresAt == null) {
                put("expires_at", JsonNull)
            } else {
                put("expires_at", expiresAt)
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
