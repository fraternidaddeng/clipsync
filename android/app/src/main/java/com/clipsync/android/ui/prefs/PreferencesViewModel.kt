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
    val bootRestore: Boolean = false,
    val maxSyncTextBytes: Int = SyncSettingsStore.DEFAULT_MAX_TEXT_BYTES,
)

/**
 * Persists the preference toggles (product-scope: 暂停同步, 私密模式, 自动应用,
 * 过期, 开机恢复) through [SyncSettingsStore] — the single authority for setting
 * keys, so the sync engine and retention cleanup read exactly what the user
 * toggled. Every change lands on disk immediately; this ViewModel only mirrors
 * it. Two side effects are delegated to the host: [onBootRestoreChanged] flips
 * the BOOT_COMPLETED receiver component, and [onRetentionChanged] runs one
 * cleanup pass so a shortened retention applies now, not at the next service
 * start (mirrors the Windows settings-save behaviour).
 */
class PreferencesViewModel(
    private val settings: SyncSettingsStore,
    private val onBootRestoreChanged: (Boolean) -> Unit = {},
    private val onRetentionChanged: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PreferencesUiState(
            pauseSync = settings.syncPaused,
            privateMode = settings.privateMode,
            autoApplyRemote = settings.autoApplyRemote,
            autoExpire = settings.autoExpireEnabled,
            retentionDays = settings.retentionMaxAgeDays,
            bootRestore = settings.bootRestoreEnabled,
            maxSyncTextBytes = settings.effectiveMaxSyncTextBytes,
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
        onRetentionChanged()
    }

    fun setRetentionDays(days: Int) {
        settings.retentionMaxAgeDays = days
        mutableState.value = mutableState.value.copy(retentionDays = days)
        onRetentionChanged()
    }

    /** The preference is written first so the receiver's boot-time re-check agrees. */
    fun setBootRestore(enabled: Boolean) {
        settings.bootRestoreEnabled = enabled
        mutableState.value = mutableState.value.copy(bootRestore = enabled)
        onBootRestoreChanged(enabled)
    }

    companion object {
        fun factory(
            settings: SyncSettingsStore,
            onBootRestoreChanged: (Boolean) -> Unit = {},
            onRetentionChanged: () -> Unit = {},
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PreferencesViewModel(settings, onBootRestoreChanged, onRetentionChanged) as T
            }
    }
}
