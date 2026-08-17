package com.clipsync.android.ui.settings

/** Keys persisted through [com.clipsync.android.storage.ClipRepository] settings. */
const val SETTING_IS_PAUSED = "is_paused"
const val SETTING_IS_PRIVATE_MODE = "is_private_mode"
const val SETTING_AUTO_APPLY_REMOTE = "auto_apply_remote"
const val SETTING_BACKGROUND_SYNC = "background_sync_enabled"
const val SETTING_BOOT_RECOVERY_ENABLED = "boot_recovery_enabled"

fun parseSettingFlag(value: String?, default: Boolean = false): Boolean {
    if (value == null) return default
    return value.equals("true", ignoreCase = true)
}

fun formatSettingFlag(value: Boolean): String = if (value) "true" else "false"
