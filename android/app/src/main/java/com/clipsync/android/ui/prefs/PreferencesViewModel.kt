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
import com.clipsync.android.update.AppUpdater
import com.clipsync.android.update.UpdateCheckResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class PreferencesUiState(
    /** 后台同步服务总开关：关闭即彻底停止前台服务与后台监听，直到用户重新开启。 */
    val serviceEnabled: Boolean = true,
    val pauseSync: Boolean = false,
    /** 暂停自动捕获（plan 5.2）：仅停自动捕获（含后台监听），手动发送与接收照常。 */
    val pauseCapture: Boolean = false,
    val privateMode: Boolean = false,
    val autoApplyRemote: Boolean = true,
    val autoExpire: Boolean = true,
    val retentionDays: Int = SyncSettingsStore.DEFAULT_MAX_AGE_DAYS,
    /** 保留条数上限（settings-roadmap P1-15）；条数上限始终生效，与过期开关无关。 */
    val maxEntries: Int = SyncSettingsStore.DEFAULT_MAX_ENTRIES,
    val bootRestore: Boolean = false,
    /** 图像剪贴板同步（协议 v2）；默认开启（ADR 0004 修订 2026-08-28）。 */
    val imageSync: Boolean = true,
    /** 远端图片自动写入剪贴板；独立于文本自动写入（ADR 0004），默认开启（2026-08-28 修订）。 */
    val autoApplyImages: Boolean = true,
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
    /** Stamped versionName shown in 偏好 · 关于. */
    val appVersion: String = "0.0.0",
    /** Idle / checking / up-to-date / available / progress / error for the GitHub updater. */
    val updateStatus: UiText? = null,
    val updateBusy: Boolean = false,
    val updateAvailable: Boolean = false,
    /** Live facts the switches are checked against (service alive, active route…); null until wired. */
    val runtime: PreferencesRuntimeFacts? = null,
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
 * it. A changed retention also runs one cleanup pass right away so it applies
 * now, not at the next service start (mirrors the Windows settings-save
 * behaviour). Process-level reactions are delegated to the host via
 * [SideEffects]: [SideEffects.onBootRestoreChanged] flips the BOOT_COMPLETED
 * receiver component and [SideEffects.onCaptureGatesChanged] re-evaluates the
 * capture session after 暂停同步 or 私密模式 flips so background read backends
 * stop or resume on the toggle. Anything only a live Activity can do — runtime
 * permission dialogs, launching the installer — is emitted through
 * [hostRequests] instead.
 *
 * 导出历史/导入历史 (docs/export-format-v1.md / docs/export-format-v2.md) run
 * against [historyRepository] on [ioDispatcher]; the host opens the SAF streams
 * and this ViewModel reports the honest outcome in
 * [PreferencesUiState.transferStatus].
 */
@Suppress("LongParameterList")
class PreferencesViewModel(
    private val settings: SyncSettingsStore,
    private val sideEffects: SideEffects = SideEffects(),
    private val permissions: Permissions = Permissions(),
    /**
     * One tick per external write to the settings file. The store is also written by
     * surfaces outside this ViewModel — the resident notification's 暂停同步/暂停捕获
     * actions land in [com.clipsync.android.sync.SyncServiceNotification.applyAction]
     * while this ViewModel may be alive right behind the shade — so each tick re-syncs
     * the mirror via [refreshFromStore] instead of leaving the toggles frozen at
     * whatever the store said at construction time. Null (tests, previews) disables it.
     */
    settingsChanges: Flow<Unit>? = null,
    private val historyRepository: () -> ClipSyncRepository? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val appVersion: String = "0.0.0",
    private val updater: AppUpdater? = null,
    /**
     * Live service / capture facts rendered under the switches (see [PreferencesRuntimeFacts]),
     * built over a flow that ticks on every [refreshRuntimeFacts] so permission-backed facts
     * re-sample when the host returns to the foreground; each emission re-derives the fact
     * lines. Null (tests, previews) shows the switches alone.
     */
    runtimeFacts: ((refreshTicks: Flow<Unit>) -> Flow<PreferencesRuntimeFacts>)? = null,
) : ViewModel() {
    /**
     * Process-level reactions to toggles; each defaults to a no-op for tests. This ViewModel
     * outlives the Activity across recreation (rotation, IME or locale change), so these must
     * only reach process-wide objects — never an Activity's result launchers, lifecycleScope
     * or UI. Work that needs the live Activity goes through [hostRequests].
     */
    data class SideEffects(
        val onBootRestoreChanged: (Boolean) -> Unit = {},
        val onCaptureGatesChanged: () -> Unit = {},
        /** 后台同步服务 flipped: the host stops the foreground service, or starts it (if paired). */
        val onServiceEnabledChanged: (Boolean) -> Unit = {},
    )

    /**
     * Live answers to "does the app hold this runtime permission right now" (true where the
     * API level has none to ask). Consulted on the explicit enable moments only; a missing
     * permission becomes a [HostRequest], never a dialog per app open.
     */
    data class Permissions(
        val bluetoothConnect: () -> Boolean = { true },
        val postNotifications: () -> Boolean = { true },
    )

    /**
     * One-shot work only the Activity currently on screen can do: a permission dialog goes
     * through the ActivityResultLauncher registered on that very instance, and the installer
     * is an Activity start. A callback captured at construction would keep pointing at the
     * first instance, whose launchers are unregistered once it is recreated.
     */
    sealed interface HostRequest {
        /** 蓝牙备援 was enabled while BLUETOOTH_CONNECT is missing (API 31+). */
        data object BluetoothPermission : HostRequest

        /** Sync or 开机恢复 was enabled while POST_NOTIFICATIONS is missing (API 33+). */
        data object NotificationsPermission : HostRequest

        /** A verified APK is ready for the system installer. */
        data class InstallApk(
            val apk: File,
        ) : HostRequest

        /** The Android 8+ unknown-sources grant is missing; open its settings page. */
        data object InstallPermissionSettings : HostRequest
    }

    private var pendingUpdate: UpdateCheckResult? = null
    private var updateStatus: UiText? = null
    private var updateBusy: Boolean = false
    private var runtime: PreferencesRuntimeFacts? = null

    private val mutableState = MutableStateFlow(stateFromStore(transferStatus = null))

    val state: StateFlow<PreferencesUiState> = mutableState.asStateFlow()

    private val hostRequestChannel = Channel<HostRequest>(Channel.BUFFERED)

    /**
     * Requests for the Activity currently on screen, buffered until one collects them, so a
     * request raised across a recreation reaches the new instance instead of a destroyed one.
     */
    val hostRequests: Flow<HostRequest> = hostRequestChannel.receiveAsFlow()

    /** Bumped per host resume; system permissions have no flow of their own to re-sample on. */
    private val resumeTicks = MutableStateFlow(0)

    init {
        if (settingsChanges != null) {
            viewModelScope.launch {
                settingsChanges.collect { refreshFromStore() }
            }
        }
        if (runtimeFacts != null) {
            viewModelScope.launch {
                runtimeFacts(resumeTicks.map { }).collect { facts ->
                    runtime = facts
                    mutableState.update { it.copy(runtime = facts) }
                }
            }
        }
    }

    /** The host is visible again: re-sample the permission-backed runtime facts. */
    fun refreshRuntimeFacts() {
        resumeTicks.value += 1
    }

    /**
     * Re-reads every store-backed field into the mirror. The setters below keep the
     * mirror fresh for this ViewModel's own writes; this covers everyone else's —
     * without it the toggles freeze at construction time and contradict the store
     * the moment a notification action flips a pause gate.
     * [PreferencesUiState.transferStatus] is ViewModel-owned, not store-backed,
     * and survives the re-read.
     */
    fun refreshFromStore() {
        mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
    }

    private fun stateFromStore(transferStatus: UiText?): PreferencesUiState =
        PreferencesUiState(
            serviceEnabled = settings.serviceEnabled,
            pauseSync = settings.syncPaused,
            pauseCapture = settings.autoCapturePaused,
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
            transferStatus = transferStatus,
            appVersion = appVersion,
            updateStatus = updateStatus,
            updateBusy = updateBusy,
            updateAvailable = pendingUpdate?.updateAvailable == true && pendingUpdate?.payload != null,
            runtime = runtime,
        )

    /**
     * 后台同步服务 master switch: the same `sync.service_enabled` key every service start
     * path re-checks ([com.clipsync.android.sync.ClipboardSyncService.start] refuses while
     * off), so the conduit's 启动服务/停止服务 buttons and this toggle can never disagree.
     * Off stops the foreground service itself — background listening ends, the connection
     * to the peer drops, the resident notification disappears — and nothing restarts it
     * (app open, boot restore) until the user turns it back on. Distinct from [setPauseSync]
     * and [setPauseCapture], which pause behaviour inside a still-running service. The
     * setting is persisted first so the host's start/stop side effect reads the new value.
     * Enabling is an explicit "enable sync" moment: on Android 13+ it asks for
     * POST_NOTIFICATIONS so the status/inbox surfaces can appear; the service starts either
     * way — the permission is never a precondition (plan 5.2).
     */
    fun setServiceEnabled(enabled: Boolean) {
        settings.serviceEnabled = enabled
        mutableState.update { it.copy(serviceEnabled = enabled) }
        sideEffects.onServiceEnabledChanged(enabled)
        if (enabled) {
            requestNotificationsPermissionIfMissing()
        }
    }

    /** The setting is persisted first so the session's gate re-check reads the new value. */
    fun setPauseSync(paused: Boolean) {
        settings.syncPaused = paused
        mutableState.update { it.copy(pauseSync = paused) }
        sideEffects.onCaptureGatesChanged()
    }

    /**
     * 暂停自动捕获: the same `sync.capture_paused` gate the resident notification's 暂停捕获
     * action flips — local copies stop being auto-captured (the background read backends
     * stop with it), while explicit share/tile sends, outbound sync of already-recorded
     * clips, and inbound delivery keep working. Persisted first so the capture session's
     * gate re-check reads the new value.
     */
    fun setPauseCapture(paused: Boolean) {
        settings.autoCapturePaused = paused
        mutableState.update { it.copy(pauseCapture = paused) }
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
        cleanupHistoryNow()
    }

    fun setRetentionDays(days: Int) {
        val clamped = days.coerceIn(SyncSettingsStore.MIN_RETENTION_DAYS, SyncSettingsStore.MAX_RETENTION_DAYS)
        settings.retentionMaxAgeDays = clamped
        mutableState.update { it.copy(retentionDays = clamped) }
        cleanupHistoryNow()
    }

    /** 保留条数上限 (settings-roadmap P1-15): the cap always applies; a lowered cap cleans now. */
    fun setMaxEntries(entries: Int) {
        val clamped = entries.coerceIn(SyncSettingsStore.MIN_MAX_ENTRIES, SyncSettingsStore.MAX_MAX_ENTRIES)
        settings.retentionMaxEntries = clamped
        mutableState.update { it.copy(maxEntries = clamped) }
        cleanupHistoryNow()
    }

    /** One cleanup pass under the retention just persisted; a failure here must not surface as a crash. */
    private fun cleanupHistoryNow() {
        val repository = historyRepository() ?: return
        viewModelScope.launch(ioDispatcher) {
            runCatching { repository.cleanup(settings.effectiveRetentionPolicy(), nowMs()) }
        }
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

    /**
     * The preference is written first so the receiver's boot-time re-check agrees. The
     * recovery path speaks through a notification, so enabling asks for it honestly up front.
     */
    fun setBootRestore(enabled: Boolean) {
        settings.bootRestoreEnabled = enabled
        mutableState.update { it.copy(bootRestore = enabled) }
        sideEffects.onBootRestoreChanged(enabled)
        if (enabled) {
            requestNotificationsPermissionIfMissing()
        }
    }

    private fun requestNotificationsPermissionIfMissing() {
        if (!permissions.postNotifications()) {
            hostRequestChannel.trySend(HostRequest.NotificationsPermission)
        }
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
     * 自动写入远端图片 (ADR 0004, 默认开——2026-08-28 修订): the service re-reads the gate per
     * inbound batch, so toggling applies to the very next received image without a reconnect.
     */
    fun setAutoApplyImages(enabled: Boolean) {
        settings.autoApplyImages = enabled
        mutableState.update { it.copy(autoApplyImages = enabled) }
    }

    /**
     * 蓝牙备援 (ADR 0005, 默认关): the supervisor's fallback dialer re-reads the toggle per
     * reconnect cycle, so flipping it applies to the next dial without a service restart.
     * The fallback needs BLUETOOTH_CONNECT on API 31+; enabling without it asks once, on this
     * explicit moment. Denial keeps the toggle honest — the dialer re-checks the permission
     * per dial and simply stays off, and the fact line under the switch says so.
     */
    fun setBluetoothFallback(enabled: Boolean) {
        settings.bluetoothFallbackEnabled = enabled
        mutableState.update { it.copy(bluetoothFallback = enabled) }
        if (enabled && !permissions.bluetoothConnect()) {
            hostRequestChannel.trySend(HostRequest.BluetoothPermission)
        }
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
     * 偏好 · 关于: compare [appVersion] to GitHub `/releases/latest`. Checking
     * never downloads; a newer APK is offered as a separate action.
     */
    fun checkForUpdates() {
        val installer = updater ?: return
        viewModelScope.launch(ioDispatcher) {
            pendingUpdate = null
            updateBusy = true
            updateStatus = UiText.Res(R.string.prefs_update_checking)
            mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
            updateStatus =
                try {
                    val result = installer.check(appVersion)
                    pendingUpdate = result
                    when {
                        result.payload == null -> UiText.Res(R.string.prefs_update_error_no_asset)
                        result.updateAvailable ->
                            UiText.Res(
                                R.string.prefs_update_available,
                                result.latest.versionLabel,
                                result.currentVersion,
                            )
                        else -> UiText.Res(R.string.prefs_update_up_to_date, result.currentVersion)
                    }
                } catch (_: IOException) {
                    UiText.Res(R.string.prefs_update_error_network)
                } catch (_: IllegalArgumentException) {
                    UiText.Res(R.string.prefs_update_error_parse)
                } catch (_: Exception) {
                    UiText.Res(R.string.prefs_update_error_network)
                }
            updateBusy = false
            mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
        }
    }

    fun downloadUpdate() {
        val installer = updater
        val check = pendingUpdate
        when {
            installer == null || check == null || !check.updateAvailable || check.payload == null ->
                Unit
            !installer.canRequestInstall() -> {
                updateStatus = UiText.Res(R.string.prefs_update_download_desc)
                mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
                hostRequestChannel.trySend(HostRequest.InstallPermissionSettings)
            }
            else ->
                viewModelScope.launch(ioDispatcher) {
                    updateBusy = true
                    updateStatus = UiText.Res(R.string.prefs_update_downloading, 0)
                    mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
                    try {
                        val apk =
                            installer.download(check) { received, total ->
                                val percent =
                                    if (total > 0) {
                                        ((received * PERCENT_MAX) / total).toInt().coerceIn(0, PERCENT_MAX)
                                    } else {
                                        0
                                    }
                                updateStatus = UiText.Res(R.string.prefs_update_downloading, percent)
                                mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
                            }
                        updateStatus = UiText.Res(R.string.prefs_update_installing)
                        mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
                        hostRequestChannel.trySend(HostRequest.InstallApk(apk))
                    } catch (exception: IOException) {
                        updateStatus =
                            if (exception.message?.contains("SHA-256") == true) {
                                UiText.Res(R.string.prefs_update_error_hash)
                            } else {
                                UiText.Res(R.string.prefs_update_error_network)
                            }
                    } catch (_: Exception) {
                        updateStatus = UiText.Res(R.string.prefs_update_error_apply)
                    }
                    updateBusy = false
                    mutableState.update { stateFromStore(transferStatus = it.transferStatus) }
                }
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
        private const val PERCENT_MAX = 100

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

        @Suppress("LongParameterList")
        fun factory(
            settings: SyncSettingsStore,
            sideEffects: SideEffects = SideEffects(),
            permissions: Permissions = Permissions(),
            settingsChanges: Flow<Unit>? = null,
            historyRepository: () -> ClipSyncRepository? = { null },
            appVersion: String = "0.0.0",
            updater: AppUpdater? = null,
            runtimeFacts: ((refreshTicks: Flow<Unit>) -> Flow<PreferencesRuntimeFacts>)? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PreferencesViewModel(
                        settings,
                        sideEffects,
                        permissions,
                        settingsChanges,
                        historyRepository,
                        appVersion = appVersion,
                        updater = updater,
                        runtimeFacts = runtimeFacts,
                    ) as T
            }
    }
}
