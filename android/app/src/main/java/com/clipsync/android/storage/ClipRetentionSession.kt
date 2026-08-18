package com.clipsync.android.storage

internal interface ClipRetentionSession {
    suspend fun hardDeleteExpiredLive(cutoffMs: Long): Int

    suspend fun hardDeleteExpiredTombstones(cutoffMs: Long): Int
}
