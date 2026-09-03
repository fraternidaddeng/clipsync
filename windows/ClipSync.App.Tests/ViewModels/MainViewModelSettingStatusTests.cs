using System.IO;
using ClipSync.App.Clipboard;
using ClipSync.App.Startup;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// The fact lines beside the switches follow the live state the view model already holds:
/// the values this process started with pin "重启后生效", the Run entry is re-read after a
/// save, paused/private rejections are counted, and the last real apply is restated.
/// </summary>
public sealed class MainViewModelSettingStatusTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private StartupRegistrationState probeAnswer = StartupRegistrationState.NotRegistered;

    public MainViewModelSettingStatusTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-setting-status-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "vm.db"), LocalDeviceId);
    }

    private MainViewModel CreateViewModel() => new(
        store,
        new ClipboardCapturePolicy(),
        adapter,
        startupRegistrationProbe: () => probeAnswer);

    [Fact]
    public async Task RestartBoundRowsTurnOchreOnlyAfterTheSavedValueChanges()
    {
        await store.InitializeAsync();
        await store.SetSettingAsync("extra_bind_addresses", "10.0.0.7");
        await store.SetSettingAsync("ui_language", "ja");
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();

        // The values just loaded are what the listener and UI culture run with: quiet hints.
        Assert.Equal(SettingStatusTone.Quiet, viewModel.ExtraBindStatus.Tone);
        Assert.Contains("逗号分隔", viewModel.ExtraBindStatus.Text, StringComparison.Ordinal);
        Assert.Equal(SettingStatusTone.Quiet, viewModel.LanguageStatus.Tone);

        viewModel.ExtraBindAddresses = "10.0.0.7, 10.0.0.8";
        viewModel.LanguageKey = "de";
        Assert.Equal(SettingStatusTone.Attention, viewModel.ExtraBindStatus.Tone);
        Assert.Equal(SettingStatusTone.Attention, viewModel.LanguageStatus.Tone);
        Assert.Contains("重启后生效", viewModel.LanguageStatus.Text, StringComparison.Ordinal);

        // Changing back means nothing is pending any more.
        viewModel.ExtraBindAddresses = " 10.0.0.7 ";
        viewModel.LanguageKey = "ja";
        Assert.Equal(SettingStatusTone.Quiet, viewModel.ExtraBindStatus.Tone);
        Assert.Equal(SettingStatusTone.Quiet, viewModel.LanguageStatus.Tone);
    }

    [Fact]
    public async Task StartupRowReReadsTheRegistryOnSave()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();
        Assert.True(viewModel.StartupStatus.IsEmpty);

        // The toggle flips first; the app layer writes the Run entry on that change, then the
        // click handler saves — the probe on save sees the entry.
        viewModel.LaunchAtStartup = true;
        Assert.Equal(SettingStatusTone.Attention, viewModel.StartupStatus.Tone);
        probeAnswer = StartupRegistrationState.Registered;
        await viewModel.SaveSettingsFromUiAsync();
        Assert.Equal(SettingStatusTone.Quiet, viewModel.StartupStatus.Tone);
        Assert.Contains("已登记", viewModel.StartupStatus.Text, StringComparison.Ordinal);

        probeAnswer = StartupRegistrationState.DisabledInTaskManager;
        viewModel.RefreshStartupStatus();
        Assert.Equal(SettingStatusTone.Attention, viewModel.StartupStatus.Tone);
    }

    [Fact]
    public async Task PausedRowCountsSkippedCapturesForTheCurrentSpanOnly()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();

        viewModel.IsPaused = true;
        viewModel.NoteCaptureRejected(CaptureRejectionReason.Paused);
        viewModel.NoteCaptureRejected(CaptureRejectionReason.Paused);
        viewModel.NoteCaptureRejected(CaptureRejectionReason.Duplicate);
        Assert.Contains("跳过 2 条", viewModel.PauseStatus.Text, StringComparison.Ordinal);

        viewModel.IsPaused = false;
        Assert.True(viewModel.PauseStatus.IsEmpty);

        // A new span starts from zero.
        viewModel.IsPrivateMode = true;
        Assert.Contains("不留痕迹", viewModel.PrivateModeStatus.Text, StringComparison.Ordinal);
        viewModel.NoteCaptureRejected(CaptureRejectionReason.PrivateMode);
        Assert.Contains("跳过 1 条", viewModel.PrivateModeStatus.Text, StringComparison.Ordinal);
    }

    [Fact]
    public async Task AutoApplyRowFollowsTheGateAndTheLastRealApply()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();
        Assert.Contains("尚未收到", viewModel.AutoApplyStatus.Text, StringComparison.Ordinal);
        Assert.True(viewModel.AutoApplyImagesStatus.IsEmpty);

        viewModel.RecordRemoteApplyOutcome(ok: true);
        Assert.Equal(SettingStatusTone.Flow, viewModel.AutoApplyStatus.Tone);

        viewModel.IsPaused = true;
        Assert.Equal(SettingStatusTone.Attention, viewModel.AutoApplyStatus.Tone);
        Assert.Equal(SettingStatusTone.Attention, viewModel.AutoApplyImagesStatus.Tone);

        viewModel.IsPaused = false;
        viewModel.RecordRemoteApplyOutcome(ok: false);
        Assert.Equal(SettingStatusTone.Attention, viewModel.AutoApplyStatus.Tone);

        viewModel.AutoApplyRemote = false;
        Assert.True(viewModel.AutoApplyStatus.IsEmpty);
    }

    [Fact]
    public async Task ImageSyncRowFollowsTheToggleAndTheConnectedCount()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();
        Assert.Contains("等待手机连接", viewModel.ImageSyncStatus.Text, StringComparison.Ordinal);

        viewModel.UpdatePeerStatus(online: true, port: 47654, connectedCount: 1);
        Assert.Contains("1 台设备已连接", viewModel.ImageSyncStatus.Text, StringComparison.Ordinal);

        viewModel.ImageSyncEnabled = false;
        Assert.Contains("只收发文本", viewModel.ImageSyncStatus.Text, StringComparison.Ordinal);
    }

    [Fact]
    public async Task BluetoothRowAndFlyoutDetailFollowTheListenerOutcome()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();
        Assert.True(viewModel.BluetoothToggleStatus.IsEmpty);
        Assert.True(viewModel.TrayDetailStatus.IsEmpty);

        viewModel.BluetoothFallbackEnabled = true;
        viewModel.UpdateBluetoothStatus(enabled: true, listening: false, connectedDeviceName: null, failureReason: "adapter off");
        Assert.Equal(SettingStatusTone.Attention, viewModel.BluetoothToggleStatus.Tone);
        Assert.Contains("adapter off", viewModel.BluetoothToggleStatus.Text, StringComparison.Ordinal);
        Assert.Equal(viewModel.BluetoothToggleStatus, viewModel.TrayDetailStatus);

        viewModel.UpdateBluetoothStatus(enabled: true, listening: true, connectedDeviceName: "Pixel", failureReason: null);
        Assert.Equal(SettingStatusTone.Flow, viewModel.BluetoothToggleStatus.Tone);
        Assert.True(viewModel.TrayDetailStatus.IsEmpty);

        viewModel.UpdateBluetoothStatus(enabled: false, listening: false, connectedDeviceName: null, failureReason: null);
        viewModel.BluetoothFallbackEnabled = false;
        Assert.True(viewModel.BluetoothToggleStatus.IsEmpty);
    }

    [Fact]
    public async Task BlockedProcessesRowSaysEditingUntilTheSaveApplies()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();
        Assert.Contains("4 个进程", viewModel.BlockedProcessesStatus.Text, StringComparison.Ordinal);

        viewModel.BlockedProcesses = "1password";
        Assert.Contains("离开输入框", viewModel.BlockedProcessesStatus.Text, StringComparison.Ordinal);

        await viewModel.SaveSettingsFromUiAsync();
        Assert.Contains("1 个进程", viewModel.BlockedProcessesStatus.Text, StringComparison.Ordinal);

        viewModel.BlockedProcesses = string.Empty;
        await viewModel.SaveSettingsFromUiAsync();
        Assert.Contains("未屏蔽", viewModel.BlockedProcessesStatus.Text, StringComparison.Ordinal);
    }

    public ValueTask DisposeAsync()
    {
        adapter.Dispose();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
        return ValueTask.CompletedTask;
    }
}
