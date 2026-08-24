package com.clipsync.android.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val CLIP_KIND_TEXT = "text"

/**
 * One clipboard event, plan.md §3.1/§3.2. The column shape deliberately matches
 * the fuller Stage 4 storage branch so the two can merge without a data rewrite.
 * A locally deleted row keeps its identity (event id + origin seq) as a
 * tombstone but drops the content — plan.md §3.3 rule 6.
 */
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
