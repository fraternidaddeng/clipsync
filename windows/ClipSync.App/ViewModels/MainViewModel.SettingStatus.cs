using ClipSync.App.Localization;
using ClipSync.App.Startup;
using ClipSync.Core.Clipboard;
using CommunityToolkit.Mvvm.ComponentModel;

namespace ClipSync.App.ViewModels;

/// <summary>
/// Fact lines beside the switches (task: "开关的状态很多都实际没有显示"). Each property is
/// what the system is really doing for that switch right now, recomputed through
/// <see cref="SettingStatusMapper"/> whenever one of its inputs moves. The values this
/// process started with (监听地址, 语言) are remembered here so "重启后生效" only turns
/// ochre once the saved value actually differs.
/// </summary>
public partial class MainViewModel
{
    /// <summary>The last read of the per-user Run entry; refreshed at init and after every save.</summary>
    private StartupRegistrationState startupRegistry = StartupRegistrationState.Unknown;

    /// <summary>The 额外监听地址 the listener was started with this process.</summary>
    private string activeExtraBindAddresses = string.Empty;

    /// <summary>The language key the process UI culture was resolved from at startup.</summary>
    private string activeLanguageKey = ClipSync.App.Ui.LanguageCatalog.FollowSystemKey;

    /// <summary>The 屏蔽进程 names currently held by the capture policy (last applied save).</summary>
    private string[] appliedBlockedProcesses = [];

    /// <summary>Captures the capture loop skipped during the current pause/private span.</summary>
    private int suppressedCaptureCount;

    /// <summary>True while 蓝牙备援 is on but its listener could not start (adapter missing or off).</summary>
    private bool bluetoothUnavailable;

    [ObservableProperty]
    private SettingStatus startupStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus extraBindStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus languageStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus pauseStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus privateModeStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus autoApplyStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus autoApplyImagesStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus imageSyncStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus bluetoothToggleStatus = SettingStatus.None;

    [ObservableProperty]
    private SettingStatus blockedProcessesStatus = SettingStatus.None;

    /// <summary>Second line of the tray flyout footer: the one ochre fact the status sentence cannot hold.</summary>
    [ObservableProperty]
    private SettingStatus trayDetailStatus = SettingStatus.None;

    /// <summary>
    /// Re-reads the Run entry and restates the 开机自启 row. The app layer writes the entry
    /// on the property-changed event, which runs before the save that follows a toggle click,
    /// so probing on save sees the write's real result.
    /// </summary>
    public void RefreshStartupStatus()
    {
        startupRegistry = startupRegistrationProbe is null
            ? StartupRegistration.Probe()
            : startupRegistrationProbe();
        StartupStatus = SettingStatusMapper.Startup(LaunchAtStartup, startupRegistry);
    }

    /// <summary>Remembers the values this process is actually running with; called once from InitializeAsync.</summary>
    private void CaptureActiveRestartBoundValues()
    {
        activeExtraBindAddresses = ExtraBindAddresses;
        activeLanguageKey = LanguageKey;
        RefreshRestartBoundStatuses();
    }

    private void RefreshRestartBoundStatuses()
    {
        ExtraBindStatus = SettingStatusMapper.RestartBound(
            activeExtraBindAddresses,
            ExtraBindAddresses,
            Strings.Conduit_ExtraBind_RestartNote + Strings.Conduit_ExtraBind_CommaNote);
        LanguageStatus = SettingStatusMapper.RestartBound(
            activeLanguageKey,
            LanguageKey,
            Strings.Prefs_Language_RestartNote);
    }

    private void RefreshCaptureGateStatuses()
    {
        PauseStatus = SettingStatusMapper.Paused(IsPaused, suppressedCaptureCount);
        PrivateModeStatus = SettingStatusMapper.Private(IsPrivateMode, suppressedCaptureCount);
        RefreshAutoApplyStatuses();
    }

    private void RefreshAutoApplyStatuses()
    {
        AutoApplyStatus = SettingStatusMapper.AutoApplyText(AutoApplyRemote, IsPaused, IsPrivateMode, remoteApplyEvidence);
        AutoApplyImagesStatus = SettingStatusMapper.AutoApplyImages(AutoApplyImages, IsPaused, IsPrivateMode);
    }

    private void RefreshImageSyncStatus() =>
        ImageSyncStatus = SettingStatusMapper.ImageSync(ImageSyncEnabled, ConnectedDeviceCount);

    private void RefreshBluetoothToggleStatus()
    {
        BluetoothToggleStatus = SettingStatusMapper.Bluetooth(
            BluetoothFallbackEnabled, bluetoothUnavailable, BluetoothSessionActive, BluetoothStatus);
        RefreshTrayDetailStatus();
    }

    private void RefreshTrayDetailStatus() =>
        TrayDetailStatus = SettingStatusMapper.FlyoutDetail(
            FirewallRuleMissing, BluetoothFallbackEnabled, bluetoothUnavailable, BluetoothStatus);

    private void RefreshBlockedProcessesStatus() =>
        BlockedProcessesStatus = SettingStatusMapper.BlockedProcesses(BlockedProcesses, appliedBlockedProcesses);

    /// <summary>
    /// A capture the loop skipped because of the user's pause/private choice: counted so the
    /// row can say "本次已跳过 N 条" instead of a bare "已暂停".
    /// </summary>
    private void NoteSuppressedCapture(CaptureRejectionReason reason)
    {
        if (reason is CaptureRejectionReason.Paused or CaptureRejectionReason.PrivateMode)
        {
            suppressedCaptureCount++;
            RefreshCaptureGateStatuses();
        }
    }

    /// <summary>A new pause/private span starts counting from zero.</summary>
    private void OnCaptureGateOpened(bool wasSuppressing)
    {
        if (!wasSuppressing && (IsPaused || IsPrivateMode))
        {
            suppressedCaptureCount = 0;
        }

        RefreshCaptureGateStatuses();
    }

    partial void OnIsPausedChanging(bool value) => captureGateWasSuppressing = IsPaused || IsPrivateMode;

    partial void OnIsPausedChanged(bool value) => OnCaptureGateOpened(captureGateWasSuppressing);

    partial void OnIsPrivateModeChanging(bool value) => captureGateWasSuppressing = IsPaused || IsPrivateMode;

    partial void OnIsPrivateModeChanged(bool value) => OnCaptureGateOpened(captureGateWasSuppressing);

    private bool captureGateWasSuppressing;

    partial void OnAutoApplyRemoteChanged(bool value) => RefreshAutoApplyStatuses();

    partial void OnAutoApplyImagesChanged(bool value) => RefreshAutoApplyStatuses();

    partial void OnConnectedDeviceCountChanged(int value) => RefreshImageSyncStatus();

    partial void OnExtraBindAddressesChanged(string value) => RefreshRestartBoundStatuses();

    partial void OnLanguageKeyChanged(string value) => RefreshRestartBoundStatuses();

    partial void OnBlockedProcessesChanged(string value) => RefreshBlockedProcessesStatus();

    partial void OnLaunchAtStartupChanged(bool value) =>
        StartupStatus = SettingStatusMapper.Startup(value, startupRegistry);

    partial void OnBluetoothFallbackEnabledChanged(bool value) => RefreshBluetoothToggleStatus();

    partial void OnFirewallRuleMissingChanged(bool value) => RefreshTrayDetailStatus();
}
