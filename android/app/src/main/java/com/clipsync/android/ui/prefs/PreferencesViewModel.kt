package com.clipsync.android.ui.prefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clipsync.android.pairing.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preference keys for the product commitments (product-scope: 暂停同步, 私密模式,
 * 自动应用, 过期). Key strings match the stage-4 settings vocabulary so the sync
 * engine and boot receiver read the same facts without a migration.
 */
object PreferenceKeys {
    const val PAUSE_SYNC = "is_paused"
    const val PRIVATE_MODE = "is_private_mode"
    const val AUTO_APPLY_REMOTE = "auto_apply_remote"
    const val RETENTION_DAYS = "retention_days"

    /** Applied when the auto-expire toggle turns on without a stored duration. */
    const val DEFAULT_RETENTION_DAYS = 30
}

data class PreferencesUiState(
    val pauseSync: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val retentionDays: Int = PreferenceKeys.DEFAULT_RETENTION_DAYS,
) {
    /** Retention of zero days means history never expires automatically. */
    val autoExpire: Boolean get() = retentionDays > 0
}

/**
 * Persists the preference toggles through the same [KeyValueStore] seam the
 * pairing store uses, so every change survives process death immediately.
 */
class PreferencesViewModel(
    private val keyValues: KeyValueStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(readStoredState())

    val state: StateFlow<PreferencesUiState> = mutableState.asStateFlow()

    fun setPauseSync(paused: Boolean) {
        persistFlag(PreferenceKeys.PAUSE_SYNC, paused)
        mutableState.value = mutableState.value.copy(pauseSync = paused)
    }

    fun setPrivateMode(enabled: Boolean) {
        persistFlag(PreferenceKeys.PRIVATE_MODE, enabled)
        mutableState.value = mutableState.value.copy(privateMode = enabled)
    }

    fun setAutoApplyRemote(enabled: Boolean) {
        persistFlag(PreferenceKeys.AUTO_APPLY_REMOTE, enabled)
        mutableState.value = mutableState.value.copy(autoApplyRemote = enabled)
    }

    /** The toggle maps to retention days: on restores the default, off keeps forever. */
    fun setAutoExpire(enabled: Boolean) {
        setRetentionDays(if (enabled) PreferenceKeys.DEFAULT_RETENTION_DAYS else 0)
    }

    fun setRetentionDays(days: Int) {
        val bounded = days.coerceAtLeast(0)
        keyValues.write(mapOf(PreferenceKeys.RETENTION_DAYS to bounded.toString()))
        mutableState.value = mutableState.value.copy(retentionDays = bounded)
    }

    private fun persistFlag(key: String, value: Boolean) {
        keyValues.write(mapOf(key to value.toString()))
    }

    private fun readStoredState(): PreferencesUiState {
        val defaults = PreferencesUiState()
        return PreferencesUiState(
            pauseSync = readFlag(PreferenceKeys.PAUSE_SYNC, defaults.pauseSync),
            privateMode = readFlag(PreferenceKeys.PRIVATE_MODE, defaults.privateMode),
            autoApplyRemote = readFlag(PreferenceKeys.AUTO_APPLY_REMOTE, defaults.autoApplyRemote),
            retentionDays = keyValues.read(PreferenceKeys.RETENTION_DAYS)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: defaults.retentionDays,
        )
    }

    private fun readFlag(key: String, default: Boolean): Boolean =
        when (keyValues.read(key)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }

    companion object {
        fun factory(keyValues: KeyValueStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PreferencesViewModel(keyValues) as T
            }
    }
}
