package com.clipsync.android.storage

import com.clipsync.android.i18n.LanguageCatalog
import com.clipsync.android.pairing.KeyValueStore

/**
 * Sync-related settings live in SharedPreferences (via [KeyValueStore]), not in Room: they are
 * tiny scalars, need no transactions with clip rows, and must stay readable even if the event
 * database is being migrated. Defaults follow the implementation plan (auto-apply on, 2,000
 * entries / 30 days retention, 1 MiB text cap).
 */
class SyncSettingsStore(
    private val keyValues: KeyValueStore,
) {
    var autoApplyRemote: Boolean
        get() = readBoolean(KEY_AUTO_APPLY_REMOTE, default = true)
        set(value) = write(KEY_AUTO_APPLY_REMOTE, value.toString())

    var syncPaused: Boolean
        get() = readBoolean(KEY_SYNC_PAUSED, default = false)
        set(value) = write(KEY_SYNC_PAUSED, value.toString())

    /**
     * 后台同步服务总开关: whether the foreground sync service may run at all. Off is truly
     * off — every start path (app open, pairing resume, conduit 启动服务, boot restore)
     * honors this key, so the service stays down until the user turns it back on. Unlike
     * [syncPaused]/[autoCapturePaused], which pause behaviour inside a running service,
     * this stops the service itself: no background listening, no connection to the peer,
     * no resident notification.
     */
    var serviceEnabled: Boolean
        get() = readBoolean(KEY_SERVICE_ENABLED, default = true)
        set(value) = write(KEY_SERVICE_ENABLED, value.toString())

    var privateMode: Boolean
        get() = readBoolean(KEY_PRIVATE_MODE, default = false)
        set(value) = write(KEY_PRIVATE_MODE, value.toString())

    /**
     * 仅暂停自动捕获 (plan 5.2 notification action): local clipboard changes are no longer
     * auto-captured, but — unlike [syncPaused] — explicit share/tile sends, outbound sync of
     * already-recorded clips, and inbound delivery all keep working.
     */
    var autoCapturePaused: Boolean
        get() = readBoolean(KEY_AUTO_CAPTURE_PAUSED, default = false)
        set(value) = write(KEY_AUTO_CAPTURE_PAUSED, value.toString())

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

    /**
     * Whether age-based expiry runs at all; the stored [retentionMaxAgeDays] keeps its value
     * (and its positive invariant) while the toggle is off, so re-enabling restores it.
     */
    var autoExpireEnabled: Boolean
        get() = readBoolean(KEY_AUTO_EXPIRE_ENABLED, default = true)
        set(value) = write(KEY_AUTO_EXPIRE_ENABLED, value.toString())

    var maxSyncTextBytes: Int
        get() = readInt(KEY_MAX_SYNC_TEXT_BYTES, default = DEFAULT_MAX_TEXT_BYTES)
        set(value) {
            require(value > 0) { "The text size cap must be positive." }
            write(KEY_MAX_SYNC_TEXT_BYTES, value.toString())
        }

    /**
     * Restart the sync service after a device reboot (plan 5.2). Off by default: the
     * BOOT_COMPLETED receiver may only be registered after the user explicitly opts in.
     */
    var bootRestoreEnabled: Boolean
        get() = readBoolean(KEY_BOOT_RESTORE, default = false)
        set(value) = write(KEY_BOOT_RESTORE, value.toString())

    /**
     * Image clipboard sync (protocol v2 / ADR 0004). Off by default per the charter: when off,
     * the device dials protocol v1, captures no images, and answers image announces with
     * `unsupported_media`. Turning it on advertises the `image_clip_v2` capability.
     */
    var imageSyncEnabled: Boolean
        get() = readBoolean(KEY_IMAGE_SYNC, default = false)
        set(value) = write(KEY_IMAGE_SYNC, value.toString())

    /**
     * Auto-write remote images into the system clipboard. Independent of the text
     * [autoApplyRemote] gate per ADR 0004 (「`auto_apply_images` 与文本自动应用独立」) and
     * off by default, matching the Windows `auto_apply_images` setting: received images
     * always land in history; only the automatic clipboard write is opt-in.
     */
    var autoApplyImages: Boolean
        get() = readBoolean(KEY_AUTO_APPLY_IMAGES, default = false)
        set(value) = write(KEY_AUTO_APPLY_IMAGES, value.toString())

    /**
     * Bluetooth fallback transport (ADR 0005). Off by default: when on and a bonded target
     * device is selected, the supervisor tries one bt1 RFCOMM dial per reconnect cycle after
     * every IP candidate failed. Never a primary transport — IP always wins the switch back.
     */
    var bluetoothFallbackEnabled: Boolean
        get() = readBoolean(KEY_BLUETOOTH_FALLBACK, default = false)
        set(value) = write(KEY_BLUETOOTH_FALLBACK, value.toString())

    /**
     * MAC address of the bonded device the fallback dials, chosen by the user from the
     * system-bonded list. Routing metadata only — trust always comes from the pair secret.
     */
    var bluetoothPeerAddress: String?
        get() = keyValues.read(KEY_BLUETOOTH_PEER_ADDRESS)?.takeIf { it.isNotEmpty() }
        set(value) = write(KEY_BLUETOOTH_PEER_ADDRESS, value.orEmpty())

    /** Display name of the selected bonded device; UI-only, never used for routing or trust. */
    var bluetoothPeerName: String?
        get() = keyValues.read(KEY_BLUETOOTH_PEER_NAME)?.takeIf { it.isNotEmpty() }
        set(value) = write(KEY_BLUETOOTH_PEER_NAME, value.orEmpty())

    /**
     * 历史字号 (settings-roadmap P0-1): multiplies only the history content text (preview
     * body), never the chrome — group headers, meta lines and buttons keep the charter type
     * scale. Applied on top of sp, so the system font size still stacks. Only the three
     * roadmap steps are valid; anything else falls back to 标准.
     */
    var historyFontScale: Float
        get() =
            keyValues
                .read(KEY_HISTORY_FONT_SCALE)
                ?.toFloatOrNull()
                ?.takeIf { it in HISTORY_FONT_SCALES } ?: HISTORY_FONT_SCALE_STANDARD
        set(value) {
            require(value in HISTORY_FONT_SCALES) { "The history font scale must be one of $HISTORY_FONT_SCALES." }
            write(KEY_HISTORY_FONT_SCALE, value.toString())
        }

    /** 预览行数 (settings-roadmap P1-7): history preview maxLines, 2 / 4 / 6, default 4. */
    var previewLines: Int
        get() =
            keyValues
                .read(KEY_PREVIEW_LINES)
                ?.toIntOrNull()
                ?.takeIf { it in PREVIEW_LINE_CHOICES } ?: DEFAULT_PREVIEW_LINES
        set(value) {
            require(value in PREVIEW_LINE_CHOICES) { "The preview line count must be one of $PREVIEW_LINE_CHOICES." }
            write(KEY_PREVIEW_LINES, value.toString())
        }

    /**
     * 跳过敏感内容 (settings-roadmap P0-4, product-scope 敏感来源排除): copies whose
     * ClipDescription carries the sensitive marker (password managers set it) are neither
     * recorded nor synced. On by default; relies on the source app setting the marker.
     * Explicit share-panel sends are a deliberate user action and are never gated by this.
     */
    var skipSensitiveEnabled: Boolean
        get() = readBoolean(KEY_SKIP_SENSITIVE, default = true)
        set(value) = write(KEY_SKIP_SENSITIVE, value.toString())

    /**
     * 收到内容通知 (settings-roadmap P1-8): the in-app switch for the inbox notifications
     * (「收到内容」 and the content-free applied status). On by default; sync and history
     * recording continue unchanged when off — only this notification surface goes quiet.
     */
    var inboxNotifyEnabled: Boolean
        get() = readBoolean(KEY_INBOX_NOTIFY, default = true)
        set(value) = write(KEY_INBOX_NOTIFY, value.toString())

    /**
     * 外观 (settings-roadmap P1-6): manual day/night override on top of the system theme.
     * Values are [THEME_SYSTEM] / [THEME_DAY] / [THEME_NIGHT]; anything unreadable falls
     * back to 跟随系统 without erroring.
     */
    var themeOverride: String
        get() = keyValues.read(KEY_THEME)?.takeIf { it in THEME_CHOICES } ?: THEME_SYSTEM
        set(value) {
            require(value in THEME_CHOICES) { "The theme override must be one of $THEME_CHOICES." }
            write(KEY_THEME, value)
        }

    /**
     * 语言 (settings-roadmap P1-16): the UI language — [LanguageCatalog.FOLLOW_SYSTEM]
     * (跟随系统, the default) or one of the [LanguageCatalog.LANGUAGES] BCP-47 tags.
     * Anything unreadable (including a tag a future catalog no longer offers) falls back
     * to 跟随系统 without erroring.
     */
    var languageTag: String
        get() =
            keyValues
                .read(KEY_LANGUAGE)
                ?.takeIf { LanguageCatalog.isValidStoredValue(it) } ?: LanguageCatalog.FOLLOW_SYSTEM
        set(value) {
            require(LanguageCatalog.isValidStoredValue(value)) {
                "The language must be ${LanguageCatalog.FOLLOW_SYSTEM} or a catalog tag."
            }
            write(KEY_LANGUAGE, value)
        }

    fun retentionPolicy(): RetentionPolicy =
        RetentionPolicy(
            maximumEntries = retentionMaxEntries,
            maximumAgeMs = retentionMaxAgeDays * MILLIS_PER_DAY,
        )

    /**
     * What cleanup should enforce right now: the entry cap always applies; the age limit only
     * while [autoExpireEnabled] is on (an effectively-infinite age matches no row otherwise).
     */
    fun effectiveRetentionPolicy(): RetentionPolicy =
        RetentionPolicy(
            maximumEntries = retentionMaxEntries,
            maximumAgeMs = if (autoExpireEnabled) retentionMaxAgeDays * MILLIS_PER_DAY else NO_AGE_LIMIT_MS,
        )

    /** The user cap may lower the per-item size, never raise it past the protocol's 1 MiB. */
    val effectiveMaxSyncTextBytes: Int
        get() = minOf(maxSyncTextBytes, DEFAULT_MAX_TEXT_BYTES)

    private fun readBoolean(
        key: String,
        default: Boolean,
    ): Boolean = keyValues.read(key)?.toBooleanStrictOrNull() ?: default

    private fun readInt(
        key: String,
        default: Int,
    ): Int = keyValues.read(key)?.toIntOrNull()?.takeIf { it > 0 } ?: default

    private fun write(
        key: String,
        value: String,
    ) = keyValues.write(mapOf(key to value))

    companion object {
        const val PREFERENCES_NAME = "clipsync.settings"
        const val DEFAULT_MAX_ENTRIES = 2_000
        const val DEFAULT_MAX_AGE_DAYS = 30
        const val DEFAULT_MAX_TEXT_BYTES = 1_048_576

        /** 历史字号 steps (settings-roadmap P0-1): 小 / 标准 / 大. */
        const val HISTORY_FONT_SCALE_SMALL = 0.9f
        const val HISTORY_FONT_SCALE_STANDARD = 1.0f
        const val HISTORY_FONT_SCALE_LARGE = 1.15f
        val HISTORY_FONT_SCALES =
            listOf(HISTORY_FONT_SCALE_SMALL, HISTORY_FONT_SCALE_STANDARD, HISTORY_FONT_SCALE_LARGE)

        /** 预览行数 choices (settings-roadmap P1-7). */
        val PREVIEW_LINE_CHOICES = listOf(2, 4, 6)
        const val DEFAULT_PREVIEW_LINES = 4

        /** 保留时长 stepper bounds, aligned with the Windows 偏好·存留 stepper. */
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 3_650

        /** 保留条数 stepper bounds (settings-roadmap P1-15). */
        const val MIN_MAX_ENTRIES = 100
        const val MAX_MAX_ENTRIES = 10_000

        /** 外观 choices (settings-roadmap P1-6): 跟随系统 / 日间 / 夜间. */
        const val THEME_SYSTEM = "system"
        const val THEME_DAY = "day"
        const val THEME_NIGHT = "night"
        val THEME_CHOICES = listOf(THEME_SYSTEM, THEME_DAY, THEME_NIGHT)

        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000

        /** Far above any real clip age; `now - this` stays negative, so no row ever matches. */
        private const val NO_AGE_LIMIT_MS = Long.MAX_VALUE / 2

        private const val KEY_AUTO_APPLY_REMOTE = "sync.auto_apply_remote"
        private const val KEY_SYNC_PAUSED = "sync.paused"
        private const val KEY_SERVICE_ENABLED = "sync.service_enabled"
        private const val KEY_PRIVATE_MODE = "sync.private_mode"
        private const val KEY_AUTO_CAPTURE_PAUSED = "sync.capture_paused"
        private const val KEY_RETENTION_MAX_ENTRIES = "sync.retention.max_entries"
        private const val KEY_RETENTION_MAX_AGE_DAYS = "sync.retention.max_age_days"
        private const val KEY_AUTO_EXPIRE_ENABLED = "sync.retention.auto_expire"
        private const val KEY_MAX_SYNC_TEXT_BYTES = "sync.max_text_bytes"
        private const val KEY_BOOT_RESTORE = "sync.boot_restore"
        /** Public: [com.clipsync.android.sync.ClipboardSyncService] watches this key to bounce the session. */
        const val KEY_IMAGE_SYNC = "sync.image_sync"
        private const val KEY_AUTO_APPLY_IMAGES = "sync.auto_apply_images"
        private const val KEY_BLUETOOTH_FALLBACK = "sync.bluetooth_fallback"
        private const val KEY_BLUETOOTH_PEER_ADDRESS = "sync.bluetooth_peer_address"
        private const val KEY_BLUETOOTH_PEER_NAME = "sync.bluetooth_peer_name"

        // Non-sync keys follow the roadmap prefix rule: ui. / capture. / notify.
        // (existing sync.* keys stay untouched; a published key is never renamed).
        private const val KEY_HISTORY_FONT_SCALE = "ui.history_font_scale"
        private const val KEY_PREVIEW_LINES = "ui.preview_lines"
        private const val KEY_THEME = "ui.theme"
        private const val KEY_LANGUAGE = "ui.language"
        private const val KEY_SKIP_SENSITIVE = "capture.skip_sensitive"
        private const val KEY_INBOX_NOTIFY = "notify.inbox"
    }
}
