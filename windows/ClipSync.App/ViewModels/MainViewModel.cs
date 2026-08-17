using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using System.Collections.ObjectModel;
using System.Windows;

namespace ClipSync.App.ViewModels;

public partial class MainViewModel(
    SqliteClipboardEventStore store,
    ClipboardCapturePolicy capturePolicy,
    ClipSync.App.Clipboard.Win32ClipboardAdapter clipboardAdapter) : ObservableObject
{
    private bool initialized;

    [ObservableProperty]
    private string searchText = string.Empty;

    [ObservableProperty]
    private HistoryItemViewModel? selectedItem;

    [ObservableProperty]
    private bool isPaused;

    [ObservableProperty]
    private bool isPrivateMode;

    [ObservableProperty]
    private int retentionDays = 30;

    [ObservableProperty]
    private string blockedProcesses = "1password, bitwarden, keepass, keepassxc";

    [ObservableProperty]
    private bool autoApplyRemote = true;

    [ObservableProperty]
    private string extraBindAddresses = string.Empty;

    [ObservableProperty]
    private string syncStatus = "Peer endpoint not running";

    [ObservableProperty]
    private PairedDeviceViewModel? selectedDevice;

    [ObservableProperty]
    private string renameText = string.Empty;

    public ObservableCollection<HistoryItemViewModel> History { get; } = new();

    public ObservableCollection<PairedDeviceViewModel> Devices { get; } = new();

    /// <summary>Raised after a device is revoked so the app layer can drop its live sessions.</summary>
    public event Action<string>? DeviceRevoked;

    public async Task InitializeAsync()
    {
        if (initialized)
        {
            return;
        }

        await store.InitializeAsync();
        IsPaused = bool.TryParse(await store.GetSettingAsync("is_paused"), out var paused) && paused;
        IsPrivateMode = bool.TryParse(await store.GetSettingAsync("is_private_mode"), out var privateMode) && privateMode;
        if (int.TryParse(await store.GetSettingAsync("retention_days"), out var days) && days is >= 1 and <= 3650)
        {
            RetentionDays = days;
        }

        BlockedProcesses = await store.GetSettingAsync("blocked_processes") ?? BlockedProcesses;
        AutoApplyRemote = !bool.TryParse(await store.GetSettingAsync("auto_apply_remote"), out var autoApply) || autoApply;
        ExtraBindAddresses = await store.GetSettingAsync("extra_bind_addresses") ?? string.Empty;
        ApplySettings();
        await store.CleanupAsync(
            new ClipboardRetentionPolicy(maximumAge: TimeSpan.FromDays(RetentionDays)),
            DateTimeOffset.UtcNow);
        await RefreshAsync();
        await RefreshDevicesAsync();
        initialized = true;
    }

    public Task RefreshFromCaptureAsync() => RefreshAsync();

    public Task SaveSettingsFromUiAsync() => SaveSettingsAsync();

    [RelayCommand]
    private async Task RefreshAsync()
    {
        var entries = await store.SearchAsync(new ClipboardHistoryQuery(SearchText));
        History.Clear();
        foreach (var entry in entries)
        {
            History.Add(HistoryItemViewModel.FromEntry(entry));
        }
    }

    [RelayCommand]
    private async Task SearchAsync() => await RefreshAsync();

    [RelayCommand(CanExecute = nameof(HasSelection))]
    private void CopySelected()
    {
        if (SelectedItem is null)
        {
            return;
        }

        capturePolicy.SuppressNextWrite(SelectedItem.Text, DateTimeOffset.UtcNow);
        clipboardAdapter.WriteText(SelectedItem.Text);
    }

    [RelayCommand(CanExecute = nameof(HasSelection))]
    private async Task DeleteSelectedAsync()
    {
        if (SelectedItem is null)
        {
            return;
        }

        await store.DeleteAsync(SelectedItem.EventId, DateTimeOffset.UtcNow);
        await RefreshAsync();
    }

    [RelayCommand]
    private async Task ClearAsync()
    {
        await store.ClearAsync(DateTimeOffset.UtcNow);
        await RefreshAsync();
    }

    [RelayCommand]
    private async Task SaveSettingsAsync()
    {
        RetentionDays = Math.Clamp(RetentionDays, 1, 3650);
        await store.SetSettingAsync("is_paused", IsPaused.ToString());
        await store.SetSettingAsync("is_private_mode", IsPrivateMode.ToString());
        await store.SetSettingAsync("retention_days", RetentionDays.ToString(System.Globalization.CultureInfo.InvariantCulture));
        await store.SetSettingAsync("blocked_processes", BlockedProcesses);
        await store.SetSettingAsync("auto_apply_remote", AutoApplyRemote.ToString());
        await store.SetSettingAsync("extra_bind_addresses", ExtraBindAddresses);
        ApplySettings();
        await store.CleanupAsync(
            new ClipboardRetentionPolicy(maximumAge: TimeSpan.FromDays(RetentionDays)),
            DateTimeOffset.UtcNow);
        await RefreshAsync();
    }

    [RelayCommand]
    private async Task RefreshDevicesAsync()
    {
        var selectedId = SelectedDevice?.DeviceId;
        var devices = await store.ListDevicesAsync();
        Devices.Clear();
        foreach (var device in devices)
        {
            Devices.Add(PairedDeviceViewModel.FromDevice(device));
        }

        SelectedDevice = Devices.FirstOrDefault(device => device.DeviceId == selectedId);
    }

    [RelayCommand(CanExecute = nameof(HasDeviceSelection))]
    private async Task RenameDeviceAsync()
    {
        var target = SelectedDevice;
        var newName = RenameText.Trim();
        if (target is null || newName.Length is < 1 or > 64)
        {
            return;
        }

        await store.RenameDeviceAsync(target.DeviceId, newName);
        RenameText = string.Empty;
        await RefreshDevicesAsync();
    }

    [RelayCommand(CanExecute = nameof(CanRevokeDevice))]
    private async Task RevokeDeviceAsync()
    {
        var target = SelectedDevice;
        if (target is null || target.IsRevoked)
        {
            return;
        }

        // The store clears the secret and bumps the trust epoch; the app layer then kicks
        // any live session. Re-pairing requires a fresh QR scan on the phone.
        await store.RevokeDeviceAsync(target.DeviceId, DateTimeOffset.UtcNow);
        DeviceRevoked?.Invoke(target.DeviceId);
        await RefreshDevicesAsync();
    }

    partial void OnSelectedDeviceChanged(PairedDeviceViewModel? value)
    {
        RenameDeviceCommand.NotifyCanExecuteChanged();
        RevokeDeviceCommand.NotifyCanExecuteChanged();
        if (value is not null)
        {
            RenameText = value.DisplayName;
        }
    }

    private bool HasDeviceSelection() => SelectedDevice is not null;

    private bool CanRevokeDevice() => SelectedDevice is { IsRevoked: false };

    partial void OnSelectedItemChanged(HistoryItemViewModel? value)
    {
        CopySelectedCommand.NotifyCanExecuteChanged();
        DeleteSelectedCommand.NotifyCanExecuteChanged();
    }

    private bool HasSelection() => SelectedItem is not null;

    private void ApplySettings()
    {
        var blocked = BlockedProcesses
            .Split([',', ';', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        capturePolicy.UpdateSettings(new CaptureSettings(
            IsPaused,
            IsPrivateMode,
            blocked,
            TimeSpan.FromDays(RetentionDays)));
    }
}
