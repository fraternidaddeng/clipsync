using ClipSync.App;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Win32;
using System.Collections.ObjectModel;
using System.IO;
using System.Windows;

namespace ClipSync.App.ViewModels;

/// <summary>
/// Full clip body plus metadata for the detail window. The history list only
/// shows a trimmed preview; this shape is also the unit-test seam so payload
/// mapping can be checked without opening WPF UI.
/// </summary>
public sealed record ClipDetailPayload(string Text, string Source, string CreatedAt);

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
    private string syncStatus = Strings.SyncStatusNotRunning;

    [ObservableProperty]
    private string exportStatus = string.Empty;

    [ObservableProperty]
    private PairedDeviceViewModel? selectedDevice;

    [ObservableProperty]
    private string renameText = string.Empty;

    public ObservableCollection<HistoryItemViewModel> History { get; } = new();

    public ObservableCollection<PairedDeviceViewModel> Devices { get; } = new();

    /// <summary>
    /// Production opens a SaveFileDialog. Tests replace this with a temp path
    /// (or null to simulate cancel) so the command never shows a real window.
    /// </summary>
    public Func<string?> PickExportPath { get; set; } = PickExportPathWithDialog;

    /// <summary>
    /// Production opens an OpenFileDialog. Tests replace this with a temp path
    /// (or null to simulate cancel) so the command never shows a real window.
    /// </summary>
    public Func<string?> PickImportPath { get; set; } = PickImportPathWithDialog;

    /// <summary>Raised after a device is revoked so the app layer can drop its live sessions.</summary>
    public event Action<string>? DeviceRevoked;

    /// <summary>Raised when the user asks to see the full body of the selected clip.</summary>
    public event Action? DetailRequested;

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

    /// <summary>
    /// Maps the current selection to the detail payload. Returns null when
    /// nothing is selected so the window layer can no-op without extra state.
    /// </summary>
    public ClipDetailPayload? GetSelectedDetail()
    {
        var item = SelectedItem;
        return item is null ? null : new ClipDetailPayload(item.Text, item.Source, item.CreatedAt);
    }

    /// <summary>
    /// Copies through the same suppression + adapter path as the history Copy
    /// button so the capture loop does not treat the write as a new clip.
    /// </summary>
    public void CopyText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        capturePolicy.SuppressNextWrite(text, DateTimeOffset.UtcNow);
        clipboardAdapter.WriteText(text);
    }

    [RelayCommand(CanExecute = nameof(HasSelection))]
    private void CopySelected()
    {
        if (SelectedItem is null)
        {
            return;
        }

        CopyText(SelectedItem.Text);
    }

    [RelayCommand(CanExecute = nameof(HasSelection))]
    private void ViewSelected() => DetailRequested?.Invoke();

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
    private async Task ExportHistoryAsync()
    {
        var path = PickExportPath?.Invoke();
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        try
        {
            // Encode + write off the UI thread. The JSONL string holds plaintext
            // bodies and must not be logged, traced, or copied to diagnostics.
            var exported = await Task.Run(async () =>
            {
                var jsonl = await ClipboardExport.EncodeJsonLinesAsync(store).ConfigureAwait(false);
                await File.WriteAllTextAsync(path, jsonl).ConfigureAwait(false);
                return jsonl.Length == 0 ? 0 : jsonl.AsSpan().Count('\n');
            }).ConfigureAwait(false);

            ExportStatus = Strings.FormatExportedClips(exported);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            ExportStatus = Strings.ExportFailed;
        }
    }

    [RelayCommand]
    private async Task ImportHistoryAsync()
    {
        var path = PickImportPath?.Invoke();
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        try
        {
            var imported = await Task.Run(async () =>
            {
                // Hard byte cap before reading: a hostile or wrong file must not
                // OOM the app just because the user picked it.
                if (new FileInfo(path).Length > ClipboardImport.MaximumImportBytes)
                {
                    throw new IOException("import file exceeds the maximum allowed size");
                }

                var jsonl = await File.ReadAllTextAsync(path).ConfigureAwait(false);
                return await ClipboardImport.ImportJsonLinesAsync(store, jsonl).ConfigureAwait(false);
            }).ConfigureAwait(true);

            ExportStatus = Strings.FormatImportedClips(imported.Imported, imported.Skipped);
            await RefreshAsync().ConfigureAwait(true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            ExportStatus = Strings.ImportFailed;
        }
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
        ViewSelectedCommand.NotifyCanExecuteChanged();
    }

    private bool HasSelection() => SelectedItem is not null;

    private static string? PickExportPathWithDialog()
    {
        var dialog = new SaveFileDialog
        {
            Title = Strings.ExportDialogTitle,
            FileName = $"clipsync-export-{DateTime.Now:yyyyMMdd}.jsonl",
            Filter = Strings.ExportDialogFilter,
            DefaultExt = ".jsonl",
            AddExtension = true
        };

        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }

    private static string? PickImportPathWithDialog()
    {
        var dialog = new OpenFileDialog
        {
            Title = Strings.ImportDialogTitle,
            Filter = Strings.ImportDialogFilter,
            DefaultExt = ".jsonl",
            CheckFileExists = true
        };

        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }

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
