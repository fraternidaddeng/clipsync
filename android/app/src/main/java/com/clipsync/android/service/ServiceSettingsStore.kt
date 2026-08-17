package com.clipsync.android.service

import com.clipsync.android.pairing.KeyValueStore
import com.clipsync.android.ui.settings.SETTING_BACKGROUND_SYNC
import com.clipsync.android.ui.settings.SETTING_BOOT_RECOVERY_ENABLED
import com.clipsync.android.ui.settings.formatSettingFlag
import com.clipsync.android.ui.settings.parseSettingFlag

/** Sync flags the boot receiver can read without opening Room. Never stores clipboard text. */
class ServiceSettingsStore(private val keyValues: KeyValueStore) {
    fun backgroundSyncEnabled(): Boolean =
        parseSettingFlag(keyValues.read(SETTING_BACKGROUND_SYNC))

    fun bootRecoveryEnabled(): Boolean =
        parseSettingFlag(keyValues.read(SETTING_BOOT_RECOVERY_ENABLED))

    fun setBackgroundSyncEnabled(value: Boolean) {
        keyValues.write(mapOf(SETTING_BACKGROUND_SYNC to formatSettingFlag(value)))
    }

    fun setBootRecoveryEnabled(value: Boolean) {
        keyValues.write(mapOf(SETTING_BOOT_RECOVERY_ENABLED to formatSettingFlag(value)))
    }
}
