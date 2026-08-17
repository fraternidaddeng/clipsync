package com.clipsync.android.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "clips",
    indices = [Index(value = ["origin_device_id", "origin_seq"], unique = true)],
)
data class ClipEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "origin_device_id")
    val originDeviceId: String,
    @ColumnInfo(name = "origin_seq")
    val originSeq: Long,
    @ColumnInfo(name = "kind")
    val kind: String = CLIP_KIND_TEXT,
    @ColumnInfo(name = "content")
    val content: String?,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "source_app")
    val sourceApp: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long?,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
    @ColumnInfo(name = "terminal_reason")
    val terminalReason: String?,
)

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["peer_id", "event_id"], unique = true),
        Index(value = ["peer_id", "state", "next_attempt_at"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "state")
    val state: String = OUTBOX_PENDING,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
)

@Entity(tableName = "origin_receive_state")
data class OriginReceiveStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "origin_device_id")
    val originDeviceId: String,
    @ColumnInfo(name = "contiguous_seq")
    val contiguousSeq: Long,
    @ColumnInfo(name = "received_ranges")
    val receivedRanges: String = "[]",
)

@Entity(
    tableName = "peer_cursors",
    primaryKeys = ["peer_id", "origin_device_id"],
)
data class PeerCursorEntity(
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "origin_device_id")
    val originDeviceId: String,
    @ColumnInfo(name = "received_seq")
    val receivedSeq: Long,
    @ColumnInfo(name = "acked_at")
    val ackedAt: Long,
    @ColumnInfo(name = "received_ranges")
    val receivedRanges: String = "[]",
)

@Entity(tableName = "local_sequences")
data class LocalSequenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "next_seq")
    val nextSeq: Long,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "value")
    val value: String,
)
