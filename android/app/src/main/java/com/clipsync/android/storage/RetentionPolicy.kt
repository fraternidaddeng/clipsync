package com.clipsync.android.storage

const val SETTING_RETENTION_DAYS = "retention_days"
const val DEFAULT_RETENTION_DAYS = 30
const val MIN_RETENTION_DAYS = 1
const val MAX_RETENTION_DAYS = 3_650
const val RETENTION_PURGE_INTERVAL_MS = 6L * 60 * 60 * 1_000
const val MS_PER_RETENTION_DAY = 24L * 60 * 60 * 1_000

data class PurgeCounts(
    val liveClipsDeleted: Int,
    val tombstonesDeleted: Int,
)

fun parseRetentionDays(raw: String?): Int {
    val parsed = raw?.trim()?.toIntOrNull() ?: DEFAULT_RETENTION_DAYS
    return parsed.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
}

fun retentionCutoffMs(
    nowMs: Long,
    days: Int = DEFAULT_RETENTION_DAYS,
): Long = nowMs - parseRetentionDays(days.toString()).toLong() * MS_PER_RETENTION_DAY

fun isRetentionPurgeDue(
    lastRunMs: Long?,
    nowMs: Long,
    intervalMs: Long = RETENTION_PURGE_INTERVAL_MS,
): Boolean {
    if (lastRunMs == null) {
        return true
    }
    return nowMs - lastRunMs >= intervalMs
}
