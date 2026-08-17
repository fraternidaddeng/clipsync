package com.clipsync.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.service.ServiceSettingsStore
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val paused: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val backgroundSync: Boolean = false,
    val bootRecoveryEnabled: Boolean = false,
    val notificationVisibilityNote: String? = null,
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
    private val serviceSettings: ServiceSettingsStore? = null,
    private val onBackgroundSyncChanged: (Boolean) -> Unit = {},
    private val onBootRecoveryChanged: (Boolean) -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
        viewModelScope.launch {
            syncStatus.snapshots().collect { applyConnection(it) }
        }
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

    fun setBackgroundSync(value: Boolean) {
        mutableState.value = mutableState.value.copy(backgroundSync = value)
        serviceSettings?.setBackgroundSyncEnabled(value)
        viewModelScope.launch {
            repository.setSetting(SETTING_BACKGROUND_SYNC, formatSettingFlag(value))
        }
        onBackgroundSyncChanged(value)
    }

    fun setBootRecoveryEnabled(value: Boolean) {
        mutableState.value = mutableState.value.copy(bootRecoveryEnabled = value)
        serviceSettings?.setBootRecoveryEnabled(value)
        viewModelScope.launch {
            repository.setSetting(SETTING_BOOT_RECOVERY_ENABLED, formatSettingFlag(value))
        }
        onBootRecoveryChanged(value)
    }

    fun close() {
        onCleared()
    }

    private suspend fun reload() {
        val paused = parseSettingFlag(repository.getSetting(SETTING_IS_PAUSED))
        val privateMode = parseSettingFlag(repository.getSetting(SETTING_IS_PRIVATE_MODE))
        val autoApply = parseSettingFlag(repository.getSetting(SETTING_AUTO_APPLY_REMOTE), default = true)
        val backgroundSync = parseSettingFlag(repository.getSetting(SETTING_BACKGROUND_SYNC)) ||
            (serviceSettings?.backgroundSyncEnabled() == true)
        val bootRecovery = parseSettingFlag(repository.getSetting(SETTING_BOOT_RECOVERY_ENABLED)) ||
            (serviceSettings?.bootRecoveryEnabled() == true)
        val sync = syncStatus.current()
        val caps = capabilities.snapshot()
        mutableState.update { previous ->
            previous.copy(
                paused = paused,
                privateMode = privateMode,
                autoApplyRemote = autoApply,
                backgroundSync = backgroundSync,
                bootRecoveryEnabled = bootRecovery,
                read = caps.read,
                write = caps.write,
            )
        }
        applyConnection(sync)
    }

    private fun applyConnection(sync: SyncConnectionStatus) {
        mutableState.update { previous ->
            previous.copy(
                notificationVisibilityNote = if (sync.serviceRunning && sync.notificationsHidden) {
                    NOTE_NOTIFICATIONS_HIDDEN
                } else {
                    null
                },
                network = networkCard(sync),
                service = serviceCard(sync),
                pairedDeviceCount = if (sync.paired) 1 else 0,
            )
        }
    }

    companion object {
        const val NOTE_NOTIFICATIONS_HIDDEN =
            "Notifications are hidden. The sync service can still run; clipboard access is separate."

        fun factory(
            repository: ClipRepository,
            syncStatus: SyncStatusProvider,
            capabilities: CapabilityStatusProvider,
            serviceSettings: ServiceSettingsStore? = null,
            onBackgroundSyncChanged: (Boolean) -> Unit = {},
            onBootRecoveryChanged: (Boolean) -> Unit = {},
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    repository,
                    syncStatus,
                    capabilities,
                    serviceSettings,
                    onBackgroundSyncChanged,
                    onBootRecoveryChanged,
                ) as T
        }
    }
}
