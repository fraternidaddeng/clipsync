package com.clipsync.android.ui.prefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.R
import com.clipsync.android.i18n.AppLanguages
import com.clipsync.android.i18n.LanguageCatalog
import com.clipsync.android.i18n.UiText
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.HistoryTransferErrorCodes
import com.clipsync.android.storage.HistoryTransferException
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class PreferencesUiState(
    val pauseSync: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val autoExpire: Boolean = true,
    val retentionDays: Int = SyncSettingsStore.DEFAULT_MAX_AGE_DAYS,
    /** 保留条数上限（settings-roadmap P1-15）；条数上限始终生效，与过期开关无关。 */
    val maxEntries: Int = SyncSettingsStore.DEFAULT_MAX_ENTRIES,
    val bootRestore: Boolean = false,
    /** 图像剪贴板同步（协议 v2）；按章程默认关闭。 */
    val imageSync: Boolean = false,
    /** 远端图片自动写入剪贴板；独立于文本自动写入（ADR 0004），默认关闭。 */
    val autoApplyImages: Boolean = false,
    val maxSyncTextBytes: Int = SyncSettingsStore.DEFAULT_MAX_TEXT_BYTES,
    /** bt1 蓝牙备援（ADR 0005）；默认关闭，仅当 IP 路径全部不可达时才拨号。 */
    val bluetoothFallback: Boolean = false,
    /** 用户选定的蓝牙目标设备名；null 表示尚未选择（备援不会拨号）。 */
    val bluetoothDeviceName: String? = null,
    /** 历史字号（settings-roadmap P0-1）：只缩放历史内容文字，0.9 / 1.0 / 1.15。 */
    val historyFontScale: Float = SyncSettingsStore.HISTORY_FONT_SCALE_STANDARD,
    /** 预览行数（settings-roadmap P1-7）：2 / 4 / 6，默认 4。 */
    val previewLines: Int = SyncSettingsStore.DEFAULT_PREVIEW_LINES,
    /** 外观（settings-roadmap P1-6）：system / day / night，默认跟随系统。 */
    val themeOverride: String = SyncSettingsStore.THEME_SYSTEM,
    /** 跳过敏感内容（settings-roadmap P0-4）：默认开，依赖来源应用的敏感标记。 */
    val skipSensitive: Boolean = true,
    /** 收到内容通知（settings-roadmap P1-8）：应用内总开关，默认开。 */
    val inboxNotify: Boolean = true,
    /** 界面语言（settings-roadmap P1#16）：目录 tag 或 [LanguageCatalog.FOLLOW_SYSTEM]。 */
    val languageTag: String = LanguageCatalog.FOLLOW_SYSTEM,
    /** Result line of the last 导出历史/导入历史/清空历史 run; null until one has run. */
    val transferStatus: UiText? = null,
)

/** One system-bonded Bluetooth device the fallback may dial; display data only. */
data class BondedBluetoothDevice(
    val name: String,
    val address: String,
)

/**
 * Persists the preference toggles (product-scope: 暂停同步, 私密模式, 自动应用,
 * 过期, 开机恢复) through [SyncSettingsStore] — the single authority for setting
 * keys, so the sync engine and retention cleanup read exactly what the user
 * toggled. Every change lands on disk immediately; this ViewModel only mirrors
 * it. Three side effects are delegated to the host via [SideEffects]:
 * [SideEffects.onBootRestoreChanged] flips the BOOT_COMPLETED receiver
 * component, [SideEffects.onRetentionChanged] runs one cleanup pass so a
 * shortened retention applies now, not at the next service start (mirrors the
 * Windows settings-save behaviour), and [SideEffects.onCaptureGatesChanged]
 * re-evaluates the capture session after 暂停同步 or 私密模式 flips so
 * background read backends stop or resume on the toggle.
 *
 * 导出历史/导入历史 (docs/export-format-v1.md / docs/export-format-v2.md) run
 * against [historyRepository] on [ioDispatcher]; the host opens the SAF streams
 * and this ViewModel reports the honest outcome in
 * [PreferencesUiState.transferStatus].
 */
class PreferencesViewModel(
    private val settings: SyncSettingsStore,
    private val sideEffects: SideEffects = SideEffects(),
    private val historyRepository: () -> ClipSyncRepository? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** The host-owned reactions to toggles; each defaults to a no-op for tests. */
    data class SideEffects(
        val onBootRestoreChanged: (Boolean) -> Unit = {},
        val onRetentionChanged: () -> Unit = {},
        val onCaptureGatesChanged: () -> Unit = {},
        /** Enabling asks the host for the BLUETOOTH_CONNECT runtime permission (API 31+). */
        val onBluetoothFallbackChanged: (Boolean) -> Unit = {},
    )

    private val mutableState =
        MutableStateFlow(
            PreferencesUiState(
                pauseSync = settings.syncPaused,
                privateMode = settings.privateMode,
                autoApplyRemote = settings.autoApplyRemote,
                autoExpire = settings.autoExpireEnabled,
                retentionDays = settings.retentionMaxAgeDays,
                maxEntries = settings.retentionMaxEntries,
                bootRestore = settings.bootRestoreEnabled,
                imageSync = settings.imageSyncEnabled,
                autoApplyImages = settings.autoApplyImages,
                maxSyncTextBytes = settings.effectiveMaxSyncTextBytes,
                bluetoothFallback = settings.bluetoothFallbackEnabled,
                bluetoothDeviceName = settings.bluetoothPeerName,
                historyFontScale = settings.historyFontScale,
                previewLines = settings.previewLines,
                themeOverride = settings.themeOverride,
                skipSensitive = settings.skipSensitiveEnabled,
                inboxNotify = settings.inboxNotifyEnabled,
                languageTag = settings.languageTag,
            ),
        )

    val state: StateFlow<PreferencesUiState> = mutableState.asStateFlow()

    /** The setting is persisted first so the session's gate re-check reads the new value. */
    fun setPauseSync(paused: Boolean) {
        settings.syncPaused = paused
        mutableState.update { it.copy(pauseSync = paused) }
        sideEffects.onCaptureGatesChanged()
    }

    /** The setting is persisted first so the session's gate re-check reads the new value. */
    fun setPrivateMode(enabled: Boolean) {
        settings.privateMode = enabled
        mutableState.update { it.copy(privateMode = enabled) }
        sideEffects.onCaptureGatesChanged()
    }

    fun setAutoApplyRemote(enabled: Boolean) {
        settings.autoApplyRemote = enabled
        mutableState.update { it.copy(autoApplyRemote = enabled) }
    }

    /** Turning expiry off keeps the stored duration, so turning it back on restores it. */
    fun setAutoExpire(enabled: Boolean) {
        settings.autoExpireEnabled = enabled
        mutableState.update { it.copy(autoExpire = enabled) }
        sideEffects.onRetentionChanged()
    }

    fun setRetentionDays(days: Int) {
        val clamped = days.coerceIn(SyncSettingsStore.MIN_RETENTION_DAYS, SyncSettingsStore.MAX_RETENTION_DAYS)
        settings.retentionMaxAgeDays = clamped
        mutableState.update { it.copy(retentionDays = clamped) }
        sideEffects.onRetentionChanged()
    }

    /** 保留条数上限 (settings-roadmap P1-15): the cap always applies; a lowered cap cleans now. */
    fun setMaxEntries(entries: Int) {
        val clamped = entries.coerceIn(SyncSettingsStore.MIN_MAX_ENTRIES, SyncSettingsStore.MAX_MAX_ENTRIES)
        settings.retentionMaxEntries = clamped
        mutableState.update { it.copy(maxEntries = clamped) }
        sideEffects.onRetentionChanged()
    }

    /** 历史字号 (settings-roadmap P0-1): content-text-only scale, one of the three roadmap steps. */
    fun setHistoryFontScale(scale: Float) {
        if (scale !in SyncSettingsStore.HISTORY_FONT_SCALES) {
            return
        }
        settings.historyFontScale = scale
        mutableState.update { it.copy(historyFontScale = scale) }
    }

    /** 预览行数 (settings-roadmap P1-7): history preview maxLines, 2 / 4 / 6. */
    fun setPreviewLines(lines: Int) {
        if (lines !in SyncSettingsStore.PREVIEW_LINE_CHOICES) {
            return
        }
        settings.previewLines = lines
        mutableState.update { it.copy(previewLines = lines) }
    }

    /**
     * 外观 (settings-roadmap P1-6): a mode over the two existing palettes, never a colour.
     * The theme is composed from this state, so picking a chip restyles the app instantly.
     */
    fun setThemeOverride(mode: String) {
        if (mode !in SyncSettingsStore.THEME_CHOICES) {
            return
        }
        settings.themeOverride = mode
        mutableState.update { it.copy(themeOverride = mode) }
    }

    /**
     * 跳过敏感内容 (settings-roadmap P0-4): the capture policy re-reads the key per event,
     * so flipping applies to the very next copy without a service restart.
     */
    fun setSkipSensitive(enabled: Boolean) {
        settings.skipSensitiveEnabled = enabled
        mutableState.update { it.copy(skipSensitive = enabled) }
    }

    /**
     * 收到内容通知 (settings-roadmap P1-8): the delivery path re-reads the key per inbound
     * batch. Off silences only the notification surface; sync and history keep working.
     */
    fun setInboxNotify(enabled: Boolean) {
        settings.inboxNotifyEnabled = enabled
        mutableState.update { it.copy(inboxNotify = enabled) }
    }

    /**
     * 语言 (settings-roadmap P1#16): persists `ui.language` and applies it through
     * AppCompat per-app locales — started activities recreate, so the switch is
     * immediate. Must run on the main thread (AppCompat requirement).
     */
    fun setLanguage(tag: String) {
        AppLanguages.select(tag, settings)
        mutableState.update { it.copy(languageTag = settings.languageTag) }
    }

    /** The preference is written first so the receiver's boot-time re-check agrees. */
    fun setBootRestore(enabled: Boolean) {
        settings.bootRestoreEnabled = enabled
        mutableState.update { it.copy(bootRestore = enabled) }
        sideEffects.onBootRestoreChanged(enabled)
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
     * 蓝牙备援 (ADR 0005, 默认关): the supervisor's fallback dialer re-reads the toggle per
     * reconnect cycle, so flipping it applies to the next dial without a service restart.
     */
    fun setBluetoothFallback(enabled: Boolean) {
        settings.bluetoothFallbackEnabled = enabled
        mutableState.update { it.copy(bluetoothFallback = enabled) }
        sideEffects.onBluetoothFallbackChanged(enabled)
    }

    /** Persists the fallback's dial target, chosen from the system-bonded device list. */
    fun setBluetoothDevice(device: BondedBluetoothDevice) {
        settings.bluetoothPeerAddress = device.address
        settings.bluetoothPeerName = device.name
        mutableState.update { it.copy(bluetoothDeviceName = device.name) }
    }

    /**
     * 导出历史: writes the whole history (live rows and deletion markers, text and
     * image events with their blob bytes) as an export-format v1/v2 JSON Lines
     * document. Events only — never pair secrets or device rows. [openOutput] runs
     * on [ioDispatcher]; a null stream means the user cancelled and nothing is
     * reported.
     */
    fun exportHistory(openOutput: () -> OutputStream?) {
        val repository = historyRepository() ?: return
        viewModelScope.launch(ioDispatcher) {
            val status =
                try {
                    val output = openOutput() ?: return@launch
                    output.use { stream ->
                        val count = repository.exportHistory(stream, nowMs())
                        UiText.Plural(R.plurals.transfer_export_done, count)
                    }
                } catch (_: IOException) {
                    UiText.Res(R.string.transfer_export_failed)
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
            val status =
                try {
                    val input = openInput() ?: return@launch
                    input.use { stream ->
                        val result = repository.importHistory(stream)
                        UiText.Res(
                            R.string.transfer_import_done,
                            result.imported,
                            result.skipped,
                            result.conflicts,
                        )
                    }
                } catch (exception: HistoryTransferException) {
                    UiText.Res(
                        R.string.transfer_import_failed,
                        describeTransferError(exception.errorCode),
                    )
                } catch (_: IOException) {
                    UiText.Res(R.string.transfer_import_read_failed)
                }
            mutableState.update { it.copy(transferStatus = status) }
        }
    }

    /**
     * 清空历史 (settings-roadmap P0-5): one local batch delete of every visible entry,
     * with the same local-delete semantics as the per-row swipe — soft-deleted terminal
     * markers, never a remote recall — plus image-blob garbage collection. The two-step
     * confirmation lives in the UI; this method is the already-confirmed action.
     */
    fun clearHistory() {
        val repository = historyRepository() ?: return
        viewModelScope.launch(ioDispatcher) {
            val now = nowMs()
            val cleared = repository.clearHistory(now)
            repository.collectMediaGarbage(now)
            mutableState.update {
                it.copy(transferStatus = UiText.Plural(R.plurals.transfer_cleared, cleared))
            }
        }
    }

    companion object {
        fun describeTransferError(errorCode: String): UiText =
            UiText.Res(
                when (errorCode) {
                    HistoryTransferErrorCodes.BAD_HEADER -> R.string.transfer_err_bad_header
                    HistoryTransferErrorCodes.UNSUPPORTED_VERSION -> R.string.transfer_err_version
                    HistoryTransferErrorCodes.MALFORMED_RECORD -> R.string.transfer_err_malformed
                    HistoryTransferErrorCodes.HASH_MISMATCH -> R.string.transfer_err_hash
                    HistoryTransferErrorCodes.COUNT_MISMATCH -> R.string.transfer_err_count
                    HistoryTransferErrorCodes.CONTENT_TOO_LARGE -> R.string.transfer_err_too_large
                    else -> R.string.transfer_err_unknown
                },
            )

        fun factory(
            settings: SyncSettingsStore,
            sideEffects: SideEffects = SideEffects(),
            historyRepository: () -> ClipSyncRepository? = { null },
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PreferencesViewModel(
                        settings,
                        sideEffects,
                        historyRepository,
                    ) as T
            }
    }
}
