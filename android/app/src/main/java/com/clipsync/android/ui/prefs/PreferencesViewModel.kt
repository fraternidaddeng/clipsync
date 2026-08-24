package com.clipsync.android.ui.prefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.HistoryTransferErrorCodes
import com.clipsync.android.storage.HistoryTransferException
import com.clipsync.android.storage.SyncSettingsStore
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreferencesUiState(
    val pauseSync: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val autoExpire: Boolean = true,
    val retentionDays: Int = SyncSettingsStore.DEFAULT_MAX_AGE_DAYS,
    val bootRestore: Boolean = false,
    /** 图像剪贴板同步（协议 v2）；按章程默认关闭。 */
    val imageSync: Boolean = false,
    /** 远端图片自动写入剪贴板；独立于文本自动写入（ADR 0004），默认关闭。 */
    val autoApplyImages: Boolean = false,
    val maxSyncTextBytes: Int = SyncSettingsStore.DEFAULT_MAX_TEXT_BYTES,
    /** Result line of the last 导出历史/导入历史 run; null until either has run. */
    val transferStatus: String? = null,
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
 *
 * 导出历史/导入历史 (docs/export-format-v1.md) run against [historyRepository]
 * on [ioDispatcher]; the host opens the SAF streams and this ViewModel reports
 * the honest outcome in [PreferencesUiState.transferStatus].
 */
class PreferencesViewModel(
    private val settings: SyncSettingsStore,
    private val onBootRestoreChanged: (Boolean) -> Unit = {},
    private val onRetentionChanged: () -> Unit = {},
    private val historyRepository: () -> ClipSyncRepository? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PreferencesUiState(
            pauseSync = settings.syncPaused,
            privateMode = settings.privateMode,
            autoApplyRemote = settings.autoApplyRemote,
            autoExpire = settings.autoExpireEnabled,
            retentionDays = settings.retentionMaxAgeDays,
            bootRestore = settings.bootRestoreEnabled,
            imageSync = settings.imageSyncEnabled,
            autoApplyImages = settings.autoApplyImages,
            maxSyncTextBytes = settings.effectiveMaxSyncTextBytes,
        ),
    )

    val state: StateFlow<PreferencesUiState> = mutableState.asStateFlow()

    fun setPauseSync(paused: Boolean) {
        settings.syncPaused = paused
        mutableState.update { it.copy(pauseSync = paused) }
    }

    fun setPrivateMode(enabled: Boolean) {
        settings.privateMode = enabled
        mutableState.update { it.copy(privateMode = enabled) }
    }

    fun setAutoApplyRemote(enabled: Boolean) {
        settings.autoApplyRemote = enabled
        mutableState.update { it.copy(autoApplyRemote = enabled) }
    }

    /** Turning expiry off keeps the stored duration, so turning it back on restores it. */
    fun setAutoExpire(enabled: Boolean) {
        settings.autoExpireEnabled = enabled
        mutableState.update { it.copy(autoExpire = enabled) }
        onRetentionChanged()
    }

    fun setRetentionDays(days: Int) {
        settings.retentionMaxAgeDays = days
        mutableState.update { it.copy(retentionDays = days) }
        onRetentionChanged()
    }

    /** The preference is written first so the receiver's boot-time re-check agrees. */
    fun setBootRestore(enabled: Boolean) {
        settings.bootRestoreEnabled = enabled
        mutableState.update { it.copy(bootRestore = enabled) }
        onBootRestoreChanged(enabled)
    }

    /**
     * 图像同步 (protocol v2, 默认关): applies to the next (re)connection — the supervisor
     * re-reads the preference per dial attempt, and capture/serve paths re-read it per event.
     */
    fun setImageSync(enabled: Boolean) {
        settings.imageSyncEnabled = enabled
        mutableState.update { it.copy(imageSync = enabled) }
    }

    /**
     * 自动写入远端图片 (ADR 0004, 默认关): the service re-reads the gate per inbound batch,
     * so toggling applies to the very next received image without a reconnect.
     */
    fun setAutoApplyImages(enabled: Boolean) {
        settings.autoApplyImages = enabled
        mutableState.update { it.copy(autoApplyImages = enabled) }
    }

    /**
     * 导出历史: writes the whole history (live rows and deletion markers) as an
     * export-format-v1 JSON Lines document. Events only — never pair secrets or
     * device rows. [openOutput] runs on [ioDispatcher]; a null stream means the
     * user cancelled and nothing is reported.
     */
    fun exportHistory(openOutput: () -> OutputStream?) {
        val repository = historyRepository() ?: return
        viewModelScope.launch(ioDispatcher) {
            val status = try {
                val output = openOutput() ?: return@launch
                output.use { stream ->
                    val count = repository.exportHistory(stream, nowMs())
                    "已导出 $count 条记录（明文），请妥善保管备份文件。"
                }
            } catch (_: IOException) {
                "导出失败：无法写入所选文件。"
            }
            mutableState.update { it.copy(transferStatus = status) }
        }
    }

    /**
     * 导入历史: merges an export file. Idempotent on (origin_device_id, origin_seq) —
     * importing the same file twice never duplicates events; validation failures
     * change nothing. The history screen refreshes itself through Room invalidation.
     */
    fun importHistory(openInput: () -> InputStream?) {
        val repository = historyRepository() ?: return
        viewModelScope.launch(ioDispatcher) {
            val status = try {
                val input = openInput() ?: return@launch
                input.use { stream ->
                    val result = repository.importHistory(stream)
                    "导入完成：新增 ${result.imported} · 已存在 ${result.skipped} · 冲突 ${result.conflicts}"
                }
            } catch (exception: HistoryTransferException) {
                "导入失败：${describeTransferError(exception.errorCode)}。未做任何改动。"
            } catch (_: IOException) {
                "导入失败：无法读取所选文件。"
            }
            mutableState.update { it.copy(transferStatus = status) }
        }
    }

    companion object {
        fun describeTransferError(errorCode: String): String = when (errorCode) {
            HistoryTransferErrorCodes.BAD_HEADER -> "这不是 ClipSync 历史导出文件"
            HistoryTransferErrorCodes.UNSUPPORTED_VERSION -> "文件版本高于本应用支持的版本"
            HistoryTransferErrorCodes.MALFORMED_RECORD -> "文件内容损坏（记录格式错误）"
            HistoryTransferErrorCodes.HASH_MISMATCH -> "文件内容损坏（哈希校验失败）"
            HistoryTransferErrorCodes.COUNT_MISMATCH -> "文件不完整（条数与头部不符）"
            HistoryTransferErrorCodes.CONTENT_TOO_LARGE -> "文件包含超过 1 MiB 的条目"
            else -> "未知错误"
        }

        fun factory(
            settings: SyncSettingsStore,
            onBootRestoreChanged: (Boolean) -> Unit = {},
            onRetentionChanged: () -> Unit = {},
            historyRepository: () -> ClipSyncRepository? = { null },
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PreferencesViewModel(
                        settings,
                        onBootRestoreChanged,
                        onRetentionChanged,
                        historyRepository,
                    ) as T
            }
    }
}
