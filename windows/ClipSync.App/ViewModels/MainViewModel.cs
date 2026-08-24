using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using System.Collections.ObjectModel;
using System.IO;
using System.Text;
using System.Windows;

namespace ClipSync.App.ViewModels;

public partial class MainViewModel(
    SqliteClipboardEventStore store,
    ClipboardCapturePolicy capturePolicy,
    ClipSync.App.Clipboard.Win32ClipboardAdapter clipboardAdapter,
    Func<string?>? exportPathPicker = null,
    Func<string?>? importPathPicker = null) : ObservableObject
{
    private bool initialized;

    [ObservableProperty]
    private string searchText = string.Empty;

    /// <summary>
    /// The query the History collection currently reflects — set when a search
    /// runs, not per keystroke. Distinguishes the charter empty state (nothing
    /// recorded yet) from "no clips matched this search".
    /// </summary>
    [ObservableProperty]
    private string activeQuery = string.Empty;

    /// <summary>True while at least one paired device is not revoked; picks the empty-state wording.</summary>
    [ObservableProperty]
    private bool hasPairedDevices;

    [ObservableProperty]
    private HistoryItemViewModel? selectedItem;

    /// <summary>Most recent clips surfaced in the tray flyout (tokens §12.6).</summary>
    private const int RecentHistoryLength = 4;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrayStatusText))]
    private bool isPaused;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrayStatusText))]
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
    private string syncStatus = "对端服务尚未启动";

    /// <summary>True while the peer endpoint is listening; drives the conduit page's network segment.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrayStatusText))]
    private bool peerOnline;

    [ObservableProperty]
    private int peerPort;

    /// <summary>Paired devices with an authenticated session right now (conduit network segment).</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrayStatusText))]
    private int connectedDeviceCount;

    /// <summary>Outbox rows not yet acked by any peer (conduit local-service segment).</summary>
    [ObservableProperty]
    private int outboxPendingCount;

    /// <summary>When a peer last confirmed receipt, phrased for the conduit detail row.</summary>
    [ObservableProperty]
    private string lastAckText = "尚未收到对端确认";

    /// <summary>True after the clipboard adapter reported a fault; cleared on the next successful capture.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrayStatusText))]
    private bool captureFaulted;

    /// <summary>Local certificate fingerprint, pre-formatted in groups of four for human comparison.</summary>
    [ObservableProperty]
    private string localFingerprint = string.Empty;

    [ObservableProperty]
    private PairedDeviceViewModel? selectedDevice;

    [ObservableProperty]
    private string renameText = string.Empty;

    /// <summary>Result line of the last 导出历史/导入历史 run; empty until either has run.</summary>
    [ObservableProperty]
    private string historyTransferStatus = string.Empty;

    public ObservableCollection<HistoryItemViewModel> History { get; } = new();

    /// <summary>Newest clips first, capped for the tray flyout; refreshed together with History.</summary>
    public ObservableCollection<HistoryItemViewModel> RecentHistory { get; } = new();

    public ObservableCollection<PairedDeviceViewModel> Devices { get; } = new();

    /// <summary>
    /// One line for the tray flyout status strip. Priority mirrors the tray icon
    /// (private &gt; paused &gt; attention &gt; flow); the paused/private wordings match
    /// the conduit capture segment so the same fact reads the same everywhere.
    /// </summary>
    public string TrayStatusText =>
        IsPrivateMode ? "私密模式 · 不留痕迹"
        : IsPaused ? "已暂停 · 不再记录"
        : CaptureFaulted ? "捕获降级 · 剪贴板访问失败"
        : !PeerOnline ? "监听中 · 同步未启动"
        : ConnectedDeviceCount > 0 ? $"监听中 · 已连 {ConnectedDeviceCount} 台"
        : "监听中 · 等待设备连入";

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
        // Devices first: the history refresh labels remote clips with device display names.
        await RefreshDevicesAsync();
        await RefreshAsync();
        initialized = true;
    }

    public Task RefreshFromCaptureAsync() => RefreshAsync();

    public Task SaveSettingsFromUiAsync() => SaveSettingsAsync();

    /// <summary>
    /// Applies a live peer-endpoint snapshot: online flag, listening port, and how many
    /// paired devices hold an authenticated session. Recomputes the network segment text.
    /// </summary>
    public void UpdatePeerStatus(bool online, int port, int connectedCount)
    {
        PeerOnline = online;
        PeerPort = port;
        ConnectedDeviceCount = online ? connectedCount : 0;
        SyncStatus = !online
            ? "对端服务未能启动，本次会话同步关闭。"
            : connectedCount > 0
                ? $"LAN 监听端口 {port} · 已连接 {connectedCount} 台设备，内容实时互通。"
                : $"LAN 监听端口 {port} · 等待已配对设备连入；事件先落库再入发件队列，断线不丢。";
    }

    /// <summary>Re-reads the outbox depth and last peer ack for the conduit local-service segment.</summary>
    public async Task RefreshOutboxAsync()
    {
        var status = await store.GetOutboxStatusAsync();
        OutboxPendingCount = status.PendingCount;
        LastAckText = status.LastPeerAckAt is { } ackedAt
            ? $"对端确认至 {ackedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture)}"
            : "尚未收到对端确认";
    }

    /// <summary>
    /// Records remote activity from the given origin devices: bumps their last-seen
    /// timestamps and refreshes the device list so the UI shows the new times.
    /// </summary>
    public async Task NotifyRemoteActivityAsync(IEnumerable<string> originDeviceIds, DateTimeOffset now)
    {
        foreach (var deviceId in originDeviceIds.Distinct(StringComparer.Ordinal))
        {
            if (!string.Equals(deviceId, store.LocalDeviceId, StringComparison.Ordinal))
            {
                await store.UpdateDeviceLastSeenAsync(deviceId, now);
            }
        }

        await RefreshDevicesAsync();
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        var entries = await store.SearchAsync(new ClipboardHistoryQuery(SearchText));
        ActiveQuery = SearchText.Trim();
        History.Clear();
        foreach (var entry in entries)
        {
            History.Add(HistoryItemViewModel.FromEntry(entry, store.LocalDeviceId, LookupDevice));
        }

        // The flyout always shows the newest clips regardless of the search box.
        RecentHistory.Clear();
        var recent = string.IsNullOrEmpty(SearchText)
            ? entries
            : await store.SearchAsync(new ClipboardHistoryQuery(string.Empty));
        foreach (var entry in recent.Take(RecentHistoryLength))
        {
            RecentHistory.Add(HistoryItemViewModel.FromEntry(entry, store.LocalDeviceId, LookupDevice));
        }

        await RefreshOutboxAsync();
    }

    private PairedDeviceViewModel? LookupDevice(string deviceId) =>
        Devices.FirstOrDefault(device => device.DeviceId == deviceId);

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

    /// <summary>Copies a specific clip (tray flyout cards) without touching the main-window selection.</summary>
    [RelayCommand]
    private void CopyItem(HistoryItemViewModel? item)
    {
        if (item is null)
        {
            return;
        }

        capturePolicy.SuppressNextWrite(item.Text, DateTimeOffset.UtcNow);
        clipboardAdapter.WriteText(item.Text);
    }

    /// <summary>Copies the raw lowercase-hex fingerprint (machine-comparable form, no grouping spaces).</summary>
    [RelayCommand(CanExecute = nameof(HasFingerprint))]
    private void CopyFingerprint()
    {
        var raw = string.Concat(LocalFingerprint.Where(Uri.IsHexDigit)).ToLowerInvariant();
        if (raw.Length == 0)
        {
            return;
        }

        capturePolicy.SuppressNextWrite(raw, DateTimeOffset.UtcNow);
        clipboardAdapter.WriteText(raw);
    }

    private bool HasFingerprint() => LocalFingerprint.Length > 0;

    partial void OnLocalFingerprintChanged(string value) => CopyFingerprintCommand.NotifyCanExecuteChanged();

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

    /// <summary>
    /// 导出历史: writes the whole clips table (live rows and deletion markers) as an
    /// export-format-v1 JSON Lines file. Events only — never pair secrets, certificates,
    /// or device rows. The status line states the plaintext nature honestly.
    /// </summary>
    [RelayCommand]
    private async Task ExportHistoryAsync()
    {
        var path = (exportPathPicker ?? PickExportPath)();
        if (path is null)
        {
            return;
        }

        try
        {
            await using (var writer = new StreamWriter(path, append: false, new UTF8Encoding(false)))
            {
                var count = await store.ExportHistoryAsync(writer, DateTimeOffset.UtcNow);
                HistoryTransferStatus = $"已导出 {count} 条记录（明文）→ {path}";
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            HistoryTransferStatus = "导出失败：无法写入所选文件。";
        }
    }

    /// <summary>
    /// 导入历史: merges an export file. Idempotent on (origin_device_id, origin_seq) —
    /// importing the same file twice, or on a device that already synced part of the
    /// history, never duplicates events. Validation failures change nothing.
    /// </summary>
    [RelayCommand]
    private async Task ImportHistoryAsync()
    {
        var path = (importPathPicker ?? PickImportPath)();
        if (path is null)
        {
            return;
        }

        try
        {
            HistoryImportResult result;
            using (var reader = new StreamReader(path, Encoding.UTF8))
            {
                result = await store.ImportHistoryAsync(reader);
            }

            HistoryTransferStatus =
                $"导入完成：新增 {result.Imported} · 已存在 {result.Skipped} · 冲突 {result.Conflicts}";
            await RefreshAsync();
        }
        catch (HistoryTransferException exception)
        {
            HistoryTransferStatus = $"导入失败：{DescribeTransferError(exception.ErrorCode)}。未做任何改动。";
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            HistoryTransferStatus = "导入失败：无法读取所选文件。";
        }
    }

    private static string DescribeTransferError(string errorCode) => errorCode switch
    {
        HistoryTransferErrorCodes.BadHeader => "这不是 ClipSync 历史导出文件",
        HistoryTransferErrorCodes.UnsupportedVersion => "文件版本高于本应用支持的版本",
        HistoryTransferErrorCodes.MalformedRecord => "文件内容损坏（记录格式错误）",
        HistoryTransferErrorCodes.HashMismatch => "文件内容损坏（哈希校验失败）",
        HistoryTransferErrorCodes.CountMismatch => "文件不完整（条数与头部不符）",
        HistoryTransferErrorCodes.ContentTooLarge => "文件包含超过 1 MiB 的条目",
        _ => "未知错误"
    };

    private static string? PickExportPath()
    {
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Title = "导出历史",
            FileName = $"clipsync-history-{DateTime.Now:yyyyMMdd-HHmmss}.jsonl",
            DefaultExt = HistoryExportFormat.SuggestedExtension,
            Filter = "ClipSync 历史导出 (*.jsonl)|*.jsonl|所有文件 (*.*)|*.*"
        };
        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }

    private static string? PickImportPath()
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "导入历史",
            DefaultExt = HistoryExportFormat.SuggestedExtension,
            Filter = "ClipSync 历史导出 (*.jsonl)|*.jsonl|所有文件 (*.*)|*.*"
        };
        return dialog.ShowDialog() == true ? dialog.FileName : null;
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
        // ListDevicesAsync orders by created_at: the position IS the pairing order,
        // which assigns each device its neighbour hue (dev-1..dev-5, cycling).
        var devices = await store.ListDevicesAsync();
        Devices.Clear();
        for (var position = 0; position < devices.Count; position++)
        {
            Devices.Add(PairedDeviceViewModel.FromDevice(devices[position], position));
        }

        SelectedDevice = Devices.FirstOrDefault(device => device.DeviceId == selectedId);
        HasPairedDevices = Devices.Any(device => !device.IsRevoked);
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
