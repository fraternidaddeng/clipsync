package com.clipsync.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.service.ServiceSettingsStore
import com.clipsync.android.storage.ClipExport
import com.clipsync.android.storage.ClipImportCounts
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.DEFAULT_RETENTION_DAYS
import com.clipsync.android.storage.MAX_SEARCH_LIMIT
import com.clipsync.android.storage.parseRetentionDays
import com.clipsync.android.ui.HealthStatus
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SettingsUiState(
    val paused: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val backgroundSync: Boolean = false,
    val bootRecoveryEnabled: Boolean = false,
    val blacklistEnabled: Boolean = true,
    val blacklistExtra: String = "",
    val notificationVisibilityNote: SettingsVisibilityNote? = null,
    val network: HealthValue =
        networkCard(
            SyncConnectionStatus(paired = false, windowsReachable = false, serviceRunning = false),
        ),
    val service: HealthValue =
        serviceCard(
            SyncConnectionStatus(paired = false, windowsReachable = false, serviceRunning = false),
        ),
    val read: HealthValue = HealthValue(HealthStatus.FOREGROUND_ONLY, HealthTone.NEUTRAL),
    val write: HealthValue = HealthValue(HealthStatus.NOT_PROBED, HealthTone.NEUTRAL),
    val pairedDeviceCount: Int = 0,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    val exportNotice: SettingsExportNotice = SettingsExportNotice.NONE,
    val importNotice: SettingsImportNotice = SettingsImportNotice.NONE,
    val importImported: Int = 0,
    val importSkipped: Int = 0,
)

enum class SettingsExportNotice {
    NONE,
    DONE,
    FAILED,
}

enum class SettingsImportNotice {
    NONE,
    DONE,
    FAILED,
}

enum class SettingsVisibilityNote {
    NOTIFICATIONS_HIDDEN,
}

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

    fun setBlacklistEnabled(value: Boolean) {
        mutableState.value = mutableState.value.copy(blacklistEnabled = value)
        viewModelScope.launch {
            repository.setSetting(SETTING_CAPTURE_BLACKLIST_ENABLED, formatSettingFlag(value))
        }
    }

    fun setBlacklistExtra(value: String) {
        mutableState.value = mutableState.value.copy(blacklistExtra = value)
        viewModelScope.launch {
            repository.setSetting(SETTING_CAPTURE_BLACKLIST_EXTRA, value)
        }
    }

    fun setRetentionDays(raw: String) {
        val days = parseRetentionDays(raw)
        mutableState.update { it.copy(retentionDays = days) }
        viewModelScope.launch {
            repository.setSetting(SETTING_RETENTION_DAYS, days.toString())
        }
    }

    fun suggestedExportFilename(nowMs: Long = System.currentTimeMillis()): String = exportFilenameFor(nowMs)

    suspend fun exportTo(writeTarget: (String) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val rows = repository.search("", MAX_SEARCH_LIMIT)
                val encoded = ClipExport.encodeJsonLines(rows)
                writeTarget(encoded)
            }
            mutableState.update { it.copy(exportNotice = SettingsExportNotice.DONE) }
        } catch (_: Exception) {
            mutableState.update { it.copy(exportNotice = SettingsExportNotice.FAILED) }
        }
    }

    suspend fun importFrom(readSource: () -> String?) {
        try {
            val counts: ClipImportCounts =
                withContext(Dispatchers.IO) {
                    val payload = readSource() ?: error("missing import payload")
                    repository.importJsonLines(payload)
                }
            mutableState.update {
                it.copy(
                    importNotice = SettingsImportNotice.DONE,
                    importImported = counts.imported,
                    importSkipped = counts.skipped,
                )
            }
        } catch (_: Exception) {
            mutableState.update { it.copy(importNotice = SettingsImportNotice.FAILED) }
        }
    }

    fun close() {
        onCleared()
    }

    private suspend fun reload() {
        val paused = parseSettingFlag(repository.getSetting(SETTING_IS_PAUSED))
        val privateMode = parseSettingFlag(repository.getSetting(SETTING_IS_PRIVATE_MODE))
        val autoApply = parseSettingFlag(repository.getSetting(SETTING_AUTO_APPLY_REMOTE), default = true)
        val backgroundSync =
            parseSettingFlag(repository.getSetting(SETTING_BACKGROUND_SYNC)) ||
                (serviceSettings?.backgroundSyncEnabled() == true)
        val bootRecovery =
            parseSettingFlag(repository.getSetting(SETTING_BOOT_RECOVERY_ENABLED)) ||
                (serviceSettings?.bootRecoveryEnabled() == true)
        val blacklistEnabled =
            parseSettingFlag(
                repository.getSetting(SETTING_CAPTURE_BLACKLIST_ENABLED),
                default = true,
            )
        val blacklistExtra = repository.getSetting(SETTING_CAPTURE_BLACKLIST_EXTRA).orEmpty()
        val retentionDays = parseRetentionDays(repository.getSetting(SETTING_RETENTION_DAYS))
        val sync = syncStatus.current()
        val caps = capabilities.snapshot()
        mutableState.update { previous ->
            previous.copy(
                paused = paused,
                privateMode = privateMode,
                autoApplyRemote = autoApply,
                backgroundSync = backgroundSync,
                bootRecoveryEnabled = bootRecovery,
                blacklistEnabled = blacklistEnabled,
                blacklistExtra = blacklistExtra,
                retentionDays = retentionDays,
                read = caps.read,
                write = caps.write,
            )
        }
        applyConnection(sync)
    }

    private fun applyConnection(sync: SyncConnectionStatus) {
        mutableState.update { previous ->
            previous.copy(
                notificationVisibilityNote =
                    if (sync.serviceRunning && sync.notificationsHidden) {
                        SettingsVisibilityNote.NOTIFICATIONS_HIDDEN
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
        private val exportDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        internal fun exportFilenameFor(
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String {
            val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return "clipsync-export-${day.format(exportDayFormat)}.jsonl"
        }

        fun factory(
            repository: ClipRepository,
            syncStatus: SyncStatusProvider,
            capabilities: CapabilityStatusProvider,
            serviceSettings: ServiceSettingsStore? = null,
            onBackgroundSyncChanged: (Boolean) -> Unit = {},
            onBootRecoveryChanged: (Boolean) -> Unit = {},
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
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
