package com.clipsync.android.ui.settings

import com.clipsync.android.storage.ClipRepository

object LocalCapturePolicy {
    suspend fun isBlocked(repository: ClipRepository): Boolean {
        val paused = parseSettingFlag(repository.getSetting(SETTING_IS_PAUSED))
        val privateMode = parseSettingFlag(repository.getSetting(SETTING_IS_PRIVATE_MODE))
        return paused || privateMode
    }
}
