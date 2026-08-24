package com.clipsync.android.storage

import com.clipsync.android.pairing.KeyValueStore

/**
 * Sync-related settings live in SharedPreferences (via [KeyValueStore]), not in Room: they are
 * tiny scalars, need no transactions with clip rows, and must stay readable even if the event
 * database is being migrated. Defaults follow the implementation plan (auto-apply on, 2,000
 * entries / 30 days retention, 1 MiB text cap).
 */
class SyncSettingsStore(private val keyValues: KeyValueStore) {
    var autoApplyRemote: Boolean
        get() = readBoolean(KEY_AUTO_APPLY_REMOTE, default = true)
        set(value) = write(KEY_AUTO_APPLY_REMOTE, value.toString())

    var syncPaused: Boolean
        get() = readBoolean(KEY_SYNC_PAUSED, default = false)
        set(value) = write(KEY_SYNC_PAUSED, value.toString())

    var privateMode: Boolean
        get() = readBoolean(KEY_PRIVATE_MODE, default = false)
        set(value) = write(KEY_PRIVATE_MODE, value.toString())

    var retentionMaxEntries: Int
        get() = readInt(KEY_RETENTION_MAX_ENTRIES, default = DEFAULT_MAX_ENTRIES)
        set(value) {
            require(value > 0) { "The history limit must be positive." }
            write(KEY_RETENTION_MAX_ENTRIES, value.toString())
        }

    var retentionMaxAgeDays: Int
        get() = readInt(KEY_RETENTION_MAX_AGE_DAYS, default = DEFAULT_MAX_AGE_DAYS)
        set(value) {
            require(value > 0) { "The retention period must be positive." }
            write(KEY_RETENTION_MAX_AGE_DAYS, value.toString())
        }

    var maxSyncTextBytes: Int
        get() = readInt(KEY_MAX_SYNC_TEXT_BYTES, default = DEFAULT_MAX_TEXT_BYTES)
        set(value) {
            require(value > 0) { "The text size cap must be positive." }
            write(KEY_MAX_SYNC_TEXT_BYTES, value.toString())
        }

    fun retentionPolicy(): RetentionPolicy = RetentionPolicy(
        maximumEntries = retentionMaxEntries,
        maximumAgeMs = retentionMaxAgeDays * MILLIS_PER_DAY,
    )

    private fun readBoolean(key: String, default: Boolean): Boolean =
        keyValues.read(key)?.toBooleanStrictOrNull() ?: default

    private fun readInt(key: String, default: Int): Int =
        keyValues.read(key)?.toIntOrNull()?.takeIf { it > 0 } ?: default

    private fun write(key: String, value: String) = keyValues.write(mapOf(key to value))

    companion object {
        const val PREFERENCES_NAME = "clipsync.settings"
        const val DEFAULT_MAX_ENTRIES = 2_000
        const val DEFAULT_MAX_AGE_DAYS = 30
        const val DEFAULT_MAX_TEXT_BYTES = 1_048_576
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000

        private const val KEY_AUTO_APPLY_REMOTE = "sync.auto_apply_remote"
        private const val KEY_SYNC_PAUSED = "sync.paused"
        private const val KEY_PRIVATE_MODE = "sync.private_mode"
        private const val KEY_RETENTION_MAX_ENTRIES = "sync.retention.max_entries"
        private const val KEY_RETENTION_MAX_AGE_DAYS = "sync.retention.max_age_days"
        private const val KEY_MAX_SYNC_TEXT_BYTES = "sync.max_text_bytes"
    }
}
