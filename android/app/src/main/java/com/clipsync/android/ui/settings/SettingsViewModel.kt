package com.clipsync.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val paused: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val network: HealthValue = networkCard(
        SyncConnectionStatus(paired = false, windowsReachable = false, serviceRunning = false),
    ),
    val service: HealthValue = serviceCard(
        SyncConnectionStatus(paired = false, windowsReachable = false, serviceRunning = false),
    ),
    val read: HealthValue = HealthValue("Foreground only", HealthTone.NEUTRAL),
    val write: HealthValue = HealthValue("Not probed", HealthTone.NEUTRAL),
    val pairedDeviceCount: Int = 0,
)

class SettingsViewModel(
    private val repository: ClipRepository,
    private val syncStatus: SyncStatusProvider,
    private val capabilities: CapabilityStatusProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun setPaused(value: Boolean) {
        mutableState.value = mutableState.value.copy(paused = value)
        viewModelScope.launch {
            repository.setSetting(SETTING_IS_PAUSED, formatSettingFlag(value))
        }
    }

    fun setPrivateMode(value: Boolean) {
        mutableState.value = mutableState.value.copy(privateMode = value)
        viewModelScope.launch {
            repository.setSetting(SETTING_IS_PRIVATE_MODE, formatSettingFlag(value))
        }
    }

    fun setAutoApplyRemote(value: Boolean) {
        mutableState.value = mutableState.value.copy(autoApplyRemote = value)
        viewModelScope.launch {
            repository.setSetting(SETTING_AUTO_APPLY_REMOTE, formatSettingFlag(value))
        }
    }

    fun close() {
        onCleared()
    }

    private suspend fun reload() {
        val paused = parseSettingFlag(repository.getSetting(SETTING_IS_PAUSED))
        val privateMode = parseSettingFlag(repository.getSetting(SETTING_IS_PRIVATE_MODE))
        val autoApply = parseSettingFlag(repository.getSetting(SETTING_AUTO_APPLY_REMOTE), default = true)
        val sync = syncStatus.current()
        val caps = capabilities.snapshot()
        mutableState.value = SettingsUiState(
            paused = paused,
            privateMode = privateMode,
            autoApplyRemote = autoApply,
            network = networkCard(sync),
            service = serviceCard(sync),
            read = caps.read,
            write = caps.write,
            pairedDeviceCount = if (sync.paired) 1 else 0,
        )
    }

    companion object {
        fun factory(
            repository: ClipRepository,
            syncStatus: SyncStatusProvider,
            capabilities: CapabilityStatusProvider,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(repository, syncStatus, capabilities) as T
        }
    }
}
