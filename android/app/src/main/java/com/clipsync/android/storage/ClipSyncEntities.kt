package com.clipsync.android.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One clipboard event: local captures, the remote inbox, and history are all rows here, exactly
 * like the Windows `clips` table. A soft-deleted row keeps its identity columns and a
 * `terminal_reason` so sequence gaps still close on peers, but the content is erased.
 */
@Entity(
    tableName = "clips",
    indices = [
        Index(value = ["origin_device_id", "origin_seq"], unique = true),
        Index(value = ["deleted_at", "created_at"]),
        Index(value = ["content_hash"]),
    ],
)
data class ClipEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "origin_device_id")
    val originDeviceId: String,
    @ColumnInfo(name = "origin_seq")
    val originSeq: Long,
    @ColumnInfo(name = "kind", defaultValue = "text")
    val kind: String = "text",
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "source_app")
    val sourceApp: String?,
    @ColumnInfo(name = "created_at")
    val createdAtMs: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAtMs: Long?,
    @ColumnInfo(name = "deleted_at")
    val deletedAtMs: Long?,
    @ColumnInfo(name = "terminal_reason")
    val terminalReason: String?,
    /** When the remote event was written to this device's system clipboard; null while inbox-only. */
    @ColumnInfo(name = "applied_at")
    val appliedAtMs: Long?,
)

/** One (event, peer) send obligation. Rows disappear only when the peer acknowledges the range. */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["peer_id", "origin_device_id", "origin_seq"], unique = true),
        Index(value = ["peer_id", "state", "next_attempt_at"]),
    ],
)
data class OutboxEntryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "origin_device_id")
    val originDeviceId: String,
    @ColumnInfo(name = "origin_seq")
    val originSeq: Long,
    @ColumnInfo(name = "state", defaultValue = OutboxStates.PENDING)
    val state: String = OutboxStates.PENDING,
    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0")
    val nextAttemptAtMs: Long = 0,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,
)

object OutboxStates {
    const val PENDING = "pending"
    const val ANNOUNCED = "announced"
}

/**
 * What one peer has acknowledged persisting per origin. `received_ranges` stores isolated ranges
 * above the contiguous cursor as protocol JSON, matching the Windows `peer_cursors` table.
 */
@Entity(
    tableName = "peer_cursors",
    primaryKeys = ["peer_id", "origin_device_id"],
)
data class PeerCursorEntity(
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "origin_device_id")
    val originDeviceId: String,
    @ColumnInfo(name = "contiguous_seq", defaultValue = "0")
    val contiguousSeq: Long,
    @ColumnInfo(name = "received_ranges", defaultValue = "[]")
    val receivedRangesJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAtMs: Long,
)

/** This device's own persisted receive progress per origin; the source of the `known_vector`. */
@Entity(tableName = "origin_receive_state")
data class OriginReceiveStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "origin_device_id")
    val originDeviceId: String,
    @ColumnInfo(name = "contiguous_seq", defaultValue = "0")
    val contiguousSeq: Long,
    @ColumnInfo(name = "received_ranges", defaultValue = "[]")
    val receivedRangesJson: String,
)

/** Monotonic local sequence allocator; `next_seq` is the sequence the next local event will take. */
@Entity(tableName = "local_sequences")
data class LocalSequenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "next_seq", defaultValue = "1")
    val nextSeq: Long,
)

/**
 * Metadata of one content-addressed image blob (protocol v2 / ADR 0004). The encoded bytes
 * live on disk in the [com.clipsync.android.media.MediaBlobStore]; this row is the queryable
 * index (MIME, size, dimensions) and the GC root set together with [ClipMediaEntity].
 */
@Entity(tableName = "media_blobs")
data class MediaBlobEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "encoded_bytes")
    val encodedBytes: Int,
    @ColumnInfo(name = "pixel_width")
    val pixelWidth: Int,
    @ColumnInfo(name = "pixel_height")
    val pixelHeight: Int,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "created_at")
    val createdAtMs: Long,
)

/** Joins one image clip event to its blob; several events may share one blob by hash. */
@Entity(
    tableName = "clip_media",
    indices = [Index(value = ["content_hash"])],
)
data class ClipMediaEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "state")
    val state: String,
)

/** Values of the `kind` column; must match protocol v2 clip header kinds. */
object ClipKinds {
    const val TEXT = "text"
    const val IMAGE = "image"
}
