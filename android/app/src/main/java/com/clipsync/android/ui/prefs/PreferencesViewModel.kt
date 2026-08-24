package com.clipsync.android.ui.prefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PreferencesUiState(
    val pauseSync: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val autoExpire: Boolean = true,
    val retentionDays: Int = SyncSettingsStore.DEFAULT_MAX_AGE_DAYS,
)

/**
 * Persists the preference toggles (product-scope: 暂停同步, 私密模式, 自动应用,
 * 过期) through [SyncSettingsStore] — the single authority for setting keys, so
 * the sync engine and retention cleanup read exactly what the user toggled.
 * Every change lands on disk immediately; this ViewModel only mirrors it.
 */
class PreferencesViewModel(
    private val settings: SyncSettingsStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PreferencesUiState(
            pauseSync = settings.syncPaused,
            privateMode = settings.privateMode,
            autoApplyRemote = settings.autoApplyRemote,
            autoExpire = settings.autoExpireEnabled,
            retentionDays = settings.retentionMaxAgeDays,
        ),
    )

    val state: StateFlow<PreferencesUiState> = mutableState.asStateFlow()

    fun setPauseSync(paused: Boolean) {
        settings.syncPaused = paused
        mutableState.value = mutableState.value.copy(pauseSync = paused)
    }

    fun setPrivateMode(enabled: Boolean) {
        settings.privateMode = enabled
        mutableState.value = mutableState.value.copy(privateMode = enabled)
    }

    fun setAutoApplyRemote(enabled: Boolean) {
        settings.autoApplyRemote = enabled
        mutableState.value = mutableState.value.copy(autoApplyRemote = enabled)
    }

    /** Turning expiry off keeps the stored duration, so turning it back on restores it. */
    fun setAutoExpire(enabled: Boolean) {
        settings.autoExpireEnabled = enabled
        mutableState.value = mutableState.value.copy(autoExpire = enabled)
    }

    fun setRetentionDays(days: Int) {
        settings.retentionMaxAgeDays = days
        mutableState.value = mutableState.value.copy(retentionDays = days)
    }

    companion object {
        fun factory(settings: SyncSettingsStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PreferencesViewModel(settings) as T
            }
    }
}
