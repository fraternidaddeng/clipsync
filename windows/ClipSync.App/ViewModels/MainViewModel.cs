using ClipSync.App.Localization;
using ClipSync.App.Update;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Clipboard.PrivilegedHost;
using ClipSync.Core.Storage;
using ClipSync.Core.Update;
using ClipSync.Peer.Server;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using System.Collections.ObjectModel;
using System.IO;
using System.Net.Http;
using System.Text;

namespace ClipSync.App.ViewModels;

/// <summary>
/// One row of the 偏好·显示 language picker（P1#16）: 跟随系统 first, then the catalog
/// languages in catalog order, each shown by its endonym (never translated).
/// </summary>
public sealed record LanguageOption(string Key, string DisplayName);

/// <summary>
/// Full clip body plus metadata for the detail window. The history list only
/// shows a trimmed preview; this shape is also the unit-test seam so payload
/// mapping can be checked without opening WPF UI.
/// </summary>
public sealed record ClipDetailPayload(
    string Text,
    string Source,
    string CreatedAt,
    bool IsImage = false,
    string? MimeType = null,
    int? PixelWidth = null,
    int? PixelHeight = null,
    int? EncodedBytes = null,
    string? ContentHash = null,
    string? ThumbnailPath = null);

public partial class MainViewModel(
    SqliteClipboardEventStore store,
    ClipboardCapturePolicy capturePolicy,
    ClipSync.App.Clipboard.Win32ClipboardAdapter clipboardAdapter,
    Func<string?>? exportPathPicker = null,
    Func<string?>? importPathPicker = null,
    Func<bool>? clearHistoryConfirmer = null,
    PrivilegedHostAssistant? privilegedHost = null,
    WindowsAppUpdater? appUpdater = null) : ObservableObject
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

    /// <summary>A device that has not connected for this long gets the stale badge.</summary>
    private const int StaleAfterDays = 14;

    /// <summary>Non-revoked devices flagged stale (duplicate re-pair ghosts or long unseen).</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasStaleDevices))]
    [NotifyCanExecuteChangedFor(nameof(CleanupStaleDevicesCommand))]
    private int staleDeviceCount;

    public bool HasStaleDevices => StaleDeviceCount > 0;

    /// <summary>One line above the device list explaining the flagged leftovers and their backlog.</summary>
    [ObservableProperty]
    private string staleBannerText = string.Empty;

    [ObservableProperty]
    private HistoryItemViewModel? selectedItem;

    /// <summary>
    /// Format chip in effect on the history page (null = 全部). Filters render-time
    /// on the classified rows (ADR 0003) — the store query stays untouched.
    /// </summary>
    public ClipContentFormat? FormatFilter { get; private set; }

    /// <summary>True while a non-全部 format chip is on; picks the empty-state wording.</summary>
    [ObservableProperty]
    private bool isFormatFilterActive;

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

    /// <summary>保留条数上限（P1-15）：超出的最旧条目在清理时过期。100–2000，与查询上限同顶。</summary>
    [ObservableProperty]
    private int retentionMaxEntries = 2000;

    /// <summary>历史字号（P0-1）："small" / "standard" / "large"，存为系数 0.9 / 1.0 / 1.15。</summary>
    [ObservableProperty]
    private string historyFontScaleKey = ClipSync.App.Ui.HistoryDisplayOptions.StandardScaleKey;

    /// <summary>预览行数（P1-7）："2" / "4" / "6"，默认 4。</summary>
    [ObservableProperty]
    private string previewLinesKey = ClipSync.App.Ui.HistoryDisplayOptions.DefaultLinesKey;

    /// <summary>外观（P1-6）："system" / "day" / "night"，默认跟随系统；托盘图标不受影响。</summary>
    [ObservableProperty]
    private string themeModeKey = ClipSync.App.Ui.AppearanceOptions.DefaultKey;

    /// <summary>
    /// 语言（P1#16）：「system」（跟随系统，默认）或目录里的 BCP-47 标签。启动时由
    /// App 层在任何窗口构造前应用为进程 UI 文化；运行中改选只落库，重启后生效。
    /// </summary>
    [ObservableProperty]
    private string languageKey = ClipSync.App.Ui.LanguageCatalog.FollowSystemKey;

    /// <summary>
    /// Picker rows: 跟随系统（localized）+ the 19 catalog languages by endonym. Computed at
    /// bind time so the 跟随系统 label resolves after the startup culture is applied.
    /// </summary>
    [System.Diagnostics.CodeAnalysis.SuppressMessage(
        "Performance",
        "CA1822:Mark members as static",
        Justification = "WPF {Binding} 只解析实例属性；语言下拉的 ItemsSource 绑在 DataContext 上。")]
    public IReadOnlyList<LanguageOption> LanguageOptions =>
        new[] { new LanguageOption(ClipSync.App.Ui.LanguageCatalog.FollowSystemKey, Strings.Common_FollowSystem) }
            .Concat(ClipSync.App.Ui.LanguageCatalog.Languages
                .Select(language => new LanguageOption(language.Tag, language.NativeName)))
            .ToArray();

    /// <summary>The effective content-text factor the app layer feeds into the resource dictionary.</summary>
    public double HistoryFontScale => ClipSync.App.Ui.HistoryDisplayOptions.ScaleFor(HistoryFontScaleKey);

    public int PreviewLines => ClipSync.App.Ui.HistoryDisplayOptions.LinesFor(PreviewLinesKey);

    /// <summary>开机自启（P0-3）：intent mirror of the per-user Run entry the app layer maintains.</summary>
    [ObservableProperty]
    private bool launchAtStartup;

    /// <summary>呼出浮窗快捷键（P1-9）：canonical chord such as "Ctrl+Alt+V"; empty = off (default).</summary>
    [ObservableProperty]
    private string flyoutHotkey = string.Empty;

    /// <summary>Fact line under the hotkey row, set by the app layer after each registration attempt.</summary>
    [ObservableProperty]
    private string flyoutHotkeyStatus = string.Empty;

    /// <summary>True when the chord is held by another program — the status line turns act-coloured.</summary>
    [ObservableProperty]
    private bool flyoutHotkeyConflict;

    /// <summary>暂停同步快捷键（P1-9 另一半）：canonical chord toggling <see cref="IsPaused"/>; empty = off (default).</summary>
    [ObservableProperty]
    private string pauseHotkey = string.Empty;

    /// <summary>Fact line under the pause-hotkey row, set by the app layer after each registration attempt.</summary>
    [ObservableProperty]
    private string pauseHotkeyStatus = string.Empty;

    /// <summary>True when the chord is held elsewhere (another program or the flyout hotkey).</summary>
    [ObservableProperty]
    private bool pauseHotkeyConflict;

    [ObservableProperty]
    private string blockedProcesses = "1password, bitwarden, keepass, keepassxc";

    [ObservableProperty]
    private bool autoApplyRemote = true;

    /// <summary>
    /// Default on (ADR 0004, revised 2026-08-28): capture and sync clipboard images
    /// (PNG/JPEG, protocol v2). Image sync is part of the complete product experience;
    /// turning it off keeps the device a text-only v1 peer.
    /// </summary>
    [ObservableProperty]
    private bool imageSyncEnabled = true;

    /// <summary>
    /// Default on (ADR 0004, revised 2026-08-28): write remote images straight into the
    /// local clipboard. Independent of the text gate; pause and 私密模式 still stop the
    /// write (RemoteApplyDecision), and an explicit persisted opt-out is honored.
    /// </summary>
    [ObservableProperty]
    private bool autoApplyImages = true;

    [ObservableProperty]
    private string extraBindAddresses = string.Empty;

    /// <summary>
    /// Opt-in (default off): keep an RFCOMM listener up so the paired phone can fall back
    /// to Bluetooth when the LAN path is unreachable (ADR 0005). Text only, protocol v1.
    /// </summary>
    [ObservableProperty]
    private bool bluetoothFallbackEnabled;

    /// <summary>One line for the conduit network segment describing the Bluetooth fallback listener.</summary>
    [ObservableProperty]
    private string bluetoothStatus = Strings.Bt_Disabled;

    /// <summary>True while a peer is syncing over the Bluetooth fallback right now.</summary>
    [ObservableProperty]
    private bool bluetoothSessionActive;

    [ObservableProperty]
    private string syncStatus = Strings.Sync_NotStarted;

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
    private string lastAckText = Strings.Ack_None;

    /// <summary>True after the clipboard adapter reported a fault; cleared on the next successful capture.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrayStatusText))]
    private bool captureFaulted;

    /// <summary>
    /// Honest local-only strip on the history page (charter: 超限内容本机保留 + 明确提示，
    /// 不静默截断). Empty = hidden; set by an oversize capture rejection, cleared by the
    /// dismiss button or the next accepted capture. Never contains clipboard content.
    /// </summary>
    [ObservableProperty]
    private string captureNotice = string.Empty;

    /// <summary>
    /// Evidence of the most recent real remote text apply this session; feeds
    /// <see cref="ClipboardApplyState"/>. Written on the dispatcher, read on Kestrel worker
    /// threads — a plain string reference swap is safe cross-thread.
    /// </summary>
    private volatile string remoteApplyEvidence = ClipboardApplyStates.Unverified;

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

    /// <summary>Stamped assembly version shown in 偏好 · 关于.</summary>
    [ObservableProperty]
    private string appVersion = LocalAppVersion.Read();

    /// <summary>Idle / checking / up-to-date / available / progress / error for the GitHub updater.</summary>
    [ObservableProperty]
    private string updateStatus = string.Empty;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(CheckForUpdatesCommand))]
    [NotifyCanExecuteChangedFor(nameof(DownloadUpdateCommand))]
    private bool updateBusy;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(DownloadUpdateCommand))]
    private bool updateAvailable;

    private UpdateCheckResult? pendingUpdate;
    private readonly WindowsAppUpdater updates = appUpdater ?? new WindowsAppUpdater();

    /// <summary>
    /// Raised after the ZIP has been verified and extracted. The app layer launches
    /// the helper script and exits so files can be replaced.
    /// </summary>
    public event Action<string>? UpdateReadyToApply;

    // ===== 特权直读（Android 后台直读）· PC 侧 adb 协助（任务 1）=====
    // 门槛是明确同意，绝不静默：未 consent 前卡片只解释、一条 adb 命令都不发（威胁模型）。

    /// <summary>An adb executable was located on this PC; false keeps the card explain-only.</summary>
    [ObservableProperty]
    private bool privilegedAdbAvailable;

    /// <summary>Where adb was found (a path) or why not — a fact shown under the card's controls.</summary>
    [ObservableProperty]
    private string privilegedAdbLocation = string.Empty;

    /// <summary>
    /// Explicit, persisted consent to use adb. Until this is true the card issues no adb call
    /// whatsoever — it only explains what the permission means and what stays manual on the phone.
    /// </summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(DetectPhoneCommand))]
    [NotifyCanExecuteChangedFor(nameof(StartPrivilegedHostCommand))]
    [NotifyCanExecuteChangedFor(nameof(ShowWirelessQrCommand))]
    [NotifyCanExecuteChangedFor(nameof(PairWirelessManuallyCommand))]
    [NotifyCanExecuteChangedFor(nameof(ConnectWirelessCommand))]
    private bool privilegedAdbConsent;

    /// <summary>One human line describing the current adb/device situation for the card.</summary>
    [ObservableProperty]
    private string privilegedStatus = string.Empty;

    /// <summary>Result of the last detect/start action; empty until one runs.</summary>
    [ObservableProperty]
    private string privilegedActionResult = string.Empty;

    /// <summary>True while a detect or start adb call is in flight; disables the buttons.</summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(DetectPhoneCommand))]
    [NotifyCanExecuteChangedFor(nameof(StartPrivilegedHostCommand))]
    private bool privilegedBusy;

    /// <summary>True when an authorized phone is attached and consent is given: the start button is live.</summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(StartPrivilegedHostCommand))]
    private bool privilegedDeviceReady;

    /// <summary>True when the last probe found the host already running (channel up) on the ready device.</summary>
    [ObservableProperty]
    private bool privilegedHostRunning;

    /// <summary>The authorized device's serial that the start action targets; null when none is ready.</summary>
    private string? privilegedTargetSerial;

    // ===== 特权直读 · 无线配对（Android 11+ 无线调试，无需数据线）=====
    // 同一张同意闸门（privileged_adb_consent）盖住这里的每一次 adb 调用。二维码出示期间的
    // mDNS 轮询只在用户明确点「出示配对二维码」之后运行，限时两分钟、随时可停——绝不静默。

    /// <summary>How long a shown QR stays armed before the wait is declared timed out.</summary>
    private static readonly TimeSpan WirelessScanWindow = TimeSpan.FromMinutes(2);

    /// <summary>How often the QR wait re-asks adb's mDNS whether the phone has scanned.</summary>
    private static readonly TimeSpan WirelessScanPollInterval = TimeSpan.FromSeconds(2);

    /// <summary>The wireless sub-section of the card is unfolded (USB stays the primary path).</summary>
    [ObservableProperty]
    private bool wirelessPanelOpen;

    /// <summary>One human line describing where the wireless flow stands; empty = hidden.</summary>
    [ObservableProperty]
    private string wirelessStatus = string.Empty;

    /// <summary>Follow-up hint after a failure (e.g. 配对码过期怎么办); empty = hidden.</summary>
    [ObservableProperty]
    private string wirelessHint = string.Empty;

    /// <summary>True while a wireless pair/connect chain is in flight; freezes the section's buttons.</summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(ShowWirelessQrCommand))]
    [NotifyCanExecuteChangedFor(nameof(PairWirelessManuallyCommand))]
    [NotifyCanExecuteChangedFor(nameof(ConnectWirelessCommand))]
    private bool wirelessBusy;

    /// <summary>
    /// The QR payload text currently on show; empty = no QR. The view rasterizes it (DPI-exact
    /// modules) — the text itself carries the pairing password, so it is never logged.
    /// </summary>
    [ObservableProperty]
    private string wirelessQrText = string.Empty;

    /// <summary>User-typed pairing target: the IP:端口 from the phone's 配对弹窗.</summary>
    [ObservableProperty]
    private string wirelessPairEndpointText = string.Empty;

    /// <summary>User-typed six-digit pairing code from the same 配对弹窗.</summary>
    [ObservableProperty]
    private string wirelessPairCodeText = string.Empty;

    /// <summary>Connect target: the 无线调试 page's own IP 地址和端口 (auto-filled when discoverable).</summary>
    [ObservableProperty]
    private string wirelessConnectEndpointText = string.Empty;

    /// <summary>The pure stage machine; guards against stale async completions moving the UI.</summary>
    private readonly WirelessPairingFlow wirelessFlow = new();

    /// <summary>
    /// Monotonic id of the current wireless chain (QR wait, pair→connect, direct connect).
    /// Bumping it is the cancellation: every step re-checks it after each await and simply
    /// exits when superseded, so no CTS ownership is needed, a stale chain can never touch a
    /// newer session's state, and — together with the consent check — no adb call ever starts
    /// after 停止出示 / 撤销同意.
    /// </summary>
    private int wirelessScanSession;

    /// <summary>
    /// The endpoint of the most recent successful wireless <c>adb connect</c> this app run.
    /// Later probes compare the device list against it so a dropped wireless session (port
    /// drift after 息屏/切网/重启) is stated on the card instead of the stale "已连接" line.
    /// </summary>
    private WirelessAdbEndpoint? lastWirelessConnectEndpoint;

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
        IsPrivateMode ? Strings.Conduit_Status_Private
        : IsPaused ? Strings.Conduit_Status_Paused
        : CaptureFaulted ? Strings.Conduit_Status_CaptureFaulted
        : !PeerOnline ? Strings.Tray_Status_ListeningNoSync
        : ConnectedDeviceCount > 0 ? Strings.Format(nameof(Strings.Tray_Status_ConnectedFormat), ConnectedDeviceCount)
        : Strings.Tray_Status_Waiting;

    /// <summary>
    /// The clipboard apply posture reported on <c>/v1/peer/health</c> so the phone's 对端写入
    /// segment states facts instead of 未探测 forever: the user's posture first (off/paused),
    /// then the evidence of the most recent real apply this session (unverified/applied/failed).
    /// </summary>
    public string ClipboardApplyState =>
        !AutoApplyRemote ? ClipboardApplyStates.Off
        : IsPaused ? ClipboardApplyStates.Paused
        : remoteApplyEvidence;

    /// <summary>Records whether a real remote text apply reached the system clipboard.</summary>
    public void RecordRemoteApplyOutcome(bool ok) =>
        remoteApplyEvidence = ok ? ClipboardApplyStates.Applied : ClipboardApplyStates.Failed;

    /// <summary>
    /// Surfaces a capture rejection the user must hear about. Only the oversize case speaks:
    /// paused/private/duplicate/suppressed rejections are expected behaviour, but a silently
    /// dropped 1 MiB+ copy would break the 明确提示 promise (manual-qa-checklist §3).
    /// </summary>
    public void NoteCaptureRejected(CaptureRejectionReason reason)
    {
        if (reason == CaptureRejectionReason.TooLarge)
        {
            CaptureNotice = Strings.Capture_OversizeNotice;
        }
    }

    /// <summary>An accepted capture supersedes the local-only fact; the strip retires.</summary>
    public void NoteCaptureStored() => CaptureNotice = string.Empty;

    [RelayCommand]
    private void DismissCaptureNotice() => CaptureNotice = string.Empty;

    /// <summary>
    /// Records the user's explicit, informed consent to use adb, then runs a first detect so the
    /// card shows real state. This is the only gate before any adb call and it is persisted, so
    /// consent is asked once — never assumed, never silent.
    /// </summary>
    [RelayCommand]
    private async Task GrantPrivilegedConsentAsync()
    {
        PrivilegedAdbConsent = true;
        await store.SetSettingAsync("privileged_adb_consent", bool.TrueString);
        await DetectPhoneAsync();
    }

    /// <summary>Withdraws adb consent: no further adb calls run, and the transient state is cleared.</summary>
    [RelayCommand]
    private async Task RevokePrivilegedConsentAsync()
    {
        PrivilegedAdbConsent = false;
        await store.SetSettingAsync("privileged_adb_consent", bool.FalseString);
        privilegedTargetSerial = null;
        PrivilegedDeviceReady = false;
        PrivilegedHostRunning = false;
        PrivilegedActionResult = string.Empty;
        PrivilegedStatus = string.Empty;
        // The wireless sub-flow lives under the same gate: withdrawing kills its QR wait too.
        ResetWirelessFlow(clearInputs: true);
    }

    private bool CanUseAdb() => PrivilegedAdbConsent && !PrivilegedBusy;

    /// <summary>
    /// Detects attached phones over adb and summarizes the next step. Guarded by consent; a no-op
    /// when the assistant is absent (no adb located). Also refreshes whether the privileged host
    /// is already running so a channel dropped after a reboot shows as "需重启" rather than gone.
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanUseAdb))]
    private async Task DetectPhoneAsync()
    {
        if (!PrivilegedAdbConsent)
        {
            return;
        }

        if (privilegedHost is null)
        {
            PrivilegedAdbAvailable = false;
            PrivilegedStatus = Strings.Conduit_Privileged_AdbMissing;
            return;
        }

        PrivilegedBusy = true;
        try
        {
            ApplyProbe(await privilegedHost.ProbeAsync());
        }
        finally
        {
            PrivilegedBusy = false;
        }
    }

    private bool CanStartPrivilegedHost() => PrivilegedAdbConsent && !PrivilegedBusy && PrivilegedDeviceReady;

    /// <summary>
    /// One-click "启动特权直读": runs the on-device start script for the detected phone, states the
    /// outcome as a fact, and re-probes so the card reflects the now-running (or still-not) channel.
    /// Only ever reached from an explicit tap, and only after consent.
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanStartPrivilegedHost))]
    private async Task StartPrivilegedHostAsync()
    {
        if (!PrivilegedAdbConsent || privilegedHost is null || privilegedTargetSerial is null)
        {
            return;
        }

        PrivilegedBusy = true;
        try
        {
            var outcome = await privilegedHost.StartAsync(privilegedTargetSerial);
            PrivilegedActionResult = outcome.Status switch
            {
                PrivilegedHostStartStatus.Started => Strings.Conduit_Privileged_StartOk,
                // The script said "spawned" but post-start checks never saw the host alive:
                // stated as such, with the wireless-reconnect recovery step, instead of the
                // rosy "已发送启动命令" that used to hide this exact failure.
                PrivilegedHostStartStatus.SpawnedButNotDetected => Strings.Conduit_Privileged_SpawnedNotDetected,
                _ => Strings.Format(
                    nameof(Strings.Conduit_Privileged_StartFailedFormat),
                    outcome.Reason ?? Strings.Conduit_Privileged_ReasonUnknown),
            };
            ApplyProbe(await privilegedHost.ProbeAsync());
        }
        finally
        {
            PrivilegedBusy = false;
        }
    }

    /// <summary>Maps a probe snapshot onto the card's observable state and the one-line status.</summary>
    private void ApplyProbe(PrivilegedHostProbe probe)
    {
        PrivilegedAdbAvailable = probe.Availability != PrivilegedHostAvailability.AdbUnavailable;
        privilegedTargetSerial = probe.Target?.Serial;
        PrivilegedDeviceReady = probe.Availability == PrivilegedHostAvailability.DeviceReady;
        PrivilegedHostRunning = probe.HostRunning == true;
        PrivilegedStatus = probe.Availability switch
        {
            PrivilegedHostAvailability.AdbUnavailable => Strings.Conduit_Privileged_AdbMissing,
            PrivilegedHostAvailability.NoDevice => Strings.Conduit_Privileged_NoDevice,
            PrivilegedHostAvailability.DeviceUnauthorized => Strings.Conduit_Privileged_Unauthorized,
            PrivilegedHostAvailability.DeviceOffline => Strings.Conduit_Privileged_Offline,
            // HostRunning == null means the read-only pgrep probe itself could not run:
            // stated as such — claiming "未运行" would invite a needless restart.
            PrivilegedHostAvailability.DeviceReady => probe.HostRunning switch
            {
                true => Strings.Format(nameof(Strings.Conduit_Privileged_RunningFormat), probe.Target!.DisplayName),
                false => Strings.Format(nameof(Strings.Conduit_Privileged_StoppedFormat), probe.Target!.DisplayName),
                null => Strings.Format(nameof(Strings.Conduit_Privileged_HostUnknownFormat), probe.Target!.DisplayName),
            },
            _ => string.Empty,
        };

        // A non-ready ip:port entry is a stale wireless-debugging session, not a cable
        // problem — the honest next step is re-checking the phone's current IP:端口, never
        // "重新插拔". Overrides the generic offline/no-device line when one is present.
        if (probe.Availability is PrivilegedHostAvailability.DeviceOffline or PrivilegedHostAvailability.NoDevice)
        {
            var staleWireless = WirelessSessionDiagnosis.StaleWirelessDevices(probe.Devices);
            if (staleWireless.Count > 0)
            {
                PrivilegedStatus = Strings.Format(
                    nameof(Strings.Conduit_Privileged_OfflineWirelessFormat), staleWireless[0].Serial);
            }
        }

        UpdateWirelessSessionStatus(probe);
    }

    /// <summary>
    /// Keeps the wireless section's status line honest after a successful connect: when a
    /// later probe shows the connected endpoint offline or gone (wireless port drift), the
    /// stale "已连接" is replaced by the loss plus the recovery step. A live endpoint leaves
    /// whatever the flow last said untouched.
    /// </summary>
    private void UpdateWirelessSessionStatus(PrivilegedHostProbe probe)
    {
        // While a pair/connect chain is in flight the chain itself narrates; a probe fired
        // from inside it (fresh connect → 检测手机) must not race the just-set outcome with
        // a transiently-offline listing.
        var endpoint = lastWirelessConnectEndpoint;
        if (endpoint is null
            || WirelessBusy
            || WirelessSessionDiagnosis.Classify(probe.Devices, endpoint) == WirelessSessionState.Ready)
        {
            return;
        }

        WirelessStatus = Strings.Format(nameof(Strings.Conduit_Wireless_SessionLostFormat), endpoint.ToString());
        WirelessHint = Strings.Conduit_Wireless_ConnectFailedHint;
    }

    // ===== 无线配对命令（Android 11+ 无线调试）=====

    private bool CanStartWirelessAction() => PrivilegedAdbConsent && !WirelessBusy;

    /// <summary>
    /// Shows a fresh pairing QR (Android Studio style) and waits for the phone to scan it:
    /// while the QR is up, adb's mDNS is polled every two seconds — bounded to two minutes,
    /// cancellable at any moment, and stated on the card, never behind the user's back. The
    /// moment the phone's pairing announcement appears, pairing and connecting run on their
    /// own; the QR's password exists only in the QR pixels and the pair call.
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanStartWirelessAction))]
    private async Task ShowWirelessQrAsync()
    {
        if (!PrivilegedAdbConsent || privilegedHost is null)
        {
            return;
        }

        ResetWirelessFlow(clearInputs: false);
        if (!wirelessFlow.TryApply(WirelessPairingEvent.QrShown))
        {
            return;
        }

        // One read-only capability check first: an adb without mDNS discovery would leave the
        // QR waiting forever, so it is refused honestly and the code path is pointed at.
        WirelessBusy = true;
        bool mdnsUsable;
        try
        {
            mdnsUsable = await privilegedHost.CheckMdnsSupportAsync();
        }
        finally
        {
            WirelessBusy = false;
        }

        if (!mdnsUsable)
        {
            wirelessFlow.TryApply(WirelessPairingEvent.Cancelled);
            WirelessStatus = Strings.Conduit_Wireless_MdnsUnsupported;
            return;
        }

        var payload = AdbPairingQrPayload.Create();
        WirelessQrText = payload.ToQrText();
        WirelessStatus = Strings.Conduit_Wireless_WaitingScan;
        var session = ++wirelessScanSession;
        await WatchForPairingScanAsync(payload, session);
    }

    /// <summary>Stops showing the QR and abandons the wait; a stopped QR's secret is dead.</summary>
    [RelayCommand]
    private void StopWirelessQr()
    {
        ResetWirelessFlow(clearInputs: false);
        WirelessStatus = Strings.Conduit_Wireless_Stopped;
    }

    /// <summary>
    /// Pairs with the endpoint + six-digit code the user copied from the phone's
    /// 「使用配对码配对设备」dialog, then continues into connect on its own.
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanStartWirelessAction))]
    private async Task PairWirelessManuallyAsync()
    {
        if (!PrivilegedAdbConsent || privilegedHost is null)
        {
            return;
        }

        if (!WirelessAdbEndpoint.TryParse(WirelessPairEndpointText, out var endpoint))
        {
            WirelessStatus = Strings.Conduit_Wireless_EndpointInvalid;
            return;
        }

        var code = WirelessPairCodeText.Trim();
        if (code.Length != 6 || !code.All(char.IsAsciiDigit))
        {
            WirelessStatus = Strings.Conduit_Wireless_CodeInvalid;
            return;
        }

        ResetWirelessFlow(clearInputs: false);
        if (!wirelessFlow.TryApply(WirelessPairingEvent.ManualPairSubmitted))
        {
            return;
        }

        await PairThenAutoConnectAsync(endpoint!, code, wirelessScanSession);
    }

    /// <summary>
    /// Connects straight to an already-paired phone's wireless-debugging endpoint (the
    /// 无线调试 page's own IP 地址和端口 line — not the pairing dialog's port).
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanStartWirelessAction))]
    private async Task ConnectWirelessAsync()
    {
        if (!PrivilegedAdbConsent || privilegedHost is null)
        {
            return;
        }

        if (!WirelessAdbEndpoint.TryParse(WirelessConnectEndpointText, out var endpoint))
        {
            WirelessStatus = Strings.Conduit_Wireless_EndpointInvalid;
            return;
        }

        ResetWirelessFlow(clearInputs: false);
        if (!wirelessFlow.TryApply(WirelessPairingEvent.ConnectRequested))
        {
            return;
        }

        WirelessBusy = true;
        try
        {
            await ConnectWirelessCoreAsync(endpoint!, wirelessScanSession);
        }
        finally
        {
            WirelessBusy = false;
        }
    }

    /// <summary>Folding the section away also abandons any QR wait — nothing runs unseen.</summary>
    partial void OnWirelessPanelOpenChanged(bool value)
    {
        if (!value)
        {
            ResetWirelessFlow(clearInputs: false);
            WirelessStatus = string.Empty;
        }
    }

    /// <summary>
    /// The QR wait loop. Every await is followed by a session check, so 停止出示 / 撤销同意 /
    /// a newer QR simply strands this loop with no way to touch current state.
    /// </summary>
    private async Task WatchForPairingScanAsync(AdbPairingQrPayload payload, int session)
    {
        var deadline = DateTimeOffset.UtcNow + WirelessScanWindow;
        while (DateTimeOffset.UtcNow < deadline)
        {
            await Task.Delay(WirelessScanPollInterval);
            if (session != wirelessScanSession || privilegedHost is null)
            {
                return;
            }

            var endpoint = await privilegedHost.DiscoverPairingEndpointAsync(payload.ServiceName);
            if (session != wirelessScanSession)
            {
                return;
            }

            if (endpoint is not null && wirelessFlow.TryApply(WirelessPairingEvent.PairingServiceDiscovered))
            {
                WirelessQrText = string.Empty;
                WirelessStatus = Strings.Format(nameof(Strings.Conduit_Wireless_DiscoveredFormat), endpoint.ToString());
                await PairThenAutoConnectAsync(endpoint, payload.Password, session);
                return;
            }
        }

        if (session != wirelessScanSession)
        {
            return;
        }

        wirelessFlow.TryApply(WirelessPairingEvent.Cancelled);
        WirelessQrText = string.Empty;
        WirelessStatus = Strings.Conduit_Wireless_ScanTimeout;
    }

    /// <summary>
    /// Whether a wireless chain started under [session] may take its next step: it must not
    /// have been superseded (停止出示, a newer entry action, panel folded) and adb consent must
    /// still stand. Checked before every adb call and every state write, so revoking consent
    /// mid-chain stops the chain at the next step — no adb command ever starts after 撤销.
    /// </summary>
    private bool WirelessChainAlive(int session) => session == wirelessScanSession && PrivilegedAdbConsent;

    /// <summary>
    /// The shared tail of both pairing entries: <c>adb pair</c>, then auto-discover the
    /// connect endpoint and <c>adb connect</c>. Every failure lands as a stated fact plus,
    /// where one exists, the honest next step (expired code → reopen the phone dialog;
    /// undiscoverable connect port → type the phone's own IP 地址和端口 line).
    /// </summary>
    private async Task PairThenAutoConnectAsync(WirelessAdbEndpoint endpoint, string secret, int session)
    {
        if (privilegedHost is null || !WirelessChainAlive(session))
        {
            return;
        }

        WirelessBusy = true;
        try
        {
            WirelessStatus = Strings.Format(nameof(Strings.Conduit_Wireless_PairingFormat), endpoint.ToString());
            var pair = await privilegedHost.PairWirelessAsync(endpoint, secret);
            if (!WirelessChainAlive(session))
            {
                return;
            }

            if (!pair.Succeeded)
            {
                wirelessFlow.TryApply(WirelessPairingEvent.PairFailed);
                WirelessStatus = Strings.Format(
                    nameof(Strings.Conduit_Wireless_PairFailedFormat),
                    pair.Detail ?? Strings.Conduit_Privileged_ReasonUnknown);
                WirelessHint = Strings.Conduit_Wireless_PairFailedHint;
                return;
            }

            wirelessFlow.TryApply(WirelessPairingEvent.PairSucceeded);
            WirelessStatus = Strings.Conduit_Wireless_SearchingConnectPort;
            var connectEndpoint = await privilegedHost.DiscoverConnectEndpointAsync(endpoint.Host);
            if (!WirelessChainAlive(session))
            {
                return;
            }

            if (connectEndpoint is null)
            {
                wirelessFlow.TryApply(WirelessPairingEvent.ConnectFailed);
                WirelessStatus = Strings.Conduit_Wireless_ConnectPortNotFound;
                // Prefill the paired host so the user only types the port from the phone screen.
                WirelessConnectEndpointText = endpoint.Host + ":";
                return;
            }

            await ConnectWirelessCoreAsync(connectEndpoint, session);
        }
        finally
        {
            WirelessBusy = false;
        }
    }

    /// <summary>
    /// Runs the verified <c>adb connect</c> (flow already at Connecting) and hands success to
    /// the normal probe. "Verified" because adb's session table answers "already connected"
    /// even for a dead wireless transport; the assistant cross-checks the device list and
    /// re-dials a stale session, and that recovery is stated on the card, never silent.
    /// </summary>
    private async Task ConnectWirelessCoreAsync(WirelessAdbEndpoint endpoint, int session)
    {
        if (privilegedHost is null || !WirelessChainAlive(session))
        {
            return;
        }

        WirelessConnectEndpointText = endpoint.ToString();
        WirelessStatus = Strings.Format(nameof(Strings.Conduit_Wireless_ConnectingFormat), endpoint.ToString());
        var result = await privilegedHost.ConnectWirelessVerifiedAsync(endpoint);
        if (!WirelessChainAlive(session))
        {
            return;
        }

        if (!result.Outcome.Succeeded)
        {
            wirelessFlow.TryApply(WirelessPairingEvent.ConnectFailed);
            WirelessStatus = Strings.Format(
                nameof(Strings.Conduit_Wireless_ConnectFailedFormat),
                result.Outcome.Detail ?? Strings.Conduit_Privileged_ReasonUnknown);
            // The usual culprit is port drift: the phone's 无线调试 page shows the current value.
            WirelessHint = Strings.Conduit_Wireless_ConnectFailedHint;
            return;
        }

        wirelessFlow.TryApply(WirelessPairingEvent.ConnectSucceeded);
        lastWirelessConnectEndpoint = endpoint;
        WirelessStatus = Strings.Format(nameof(Strings.Conduit_Wireless_ConnectOkFormat), endpoint.ToString());
        WirelessHint = result.RecoveredStaleSession
            ? Strings.Conduit_Wireless_StaleSessionRedialed
            : string.Empty;
        // The wireless device now shows up in the ordinary probe (serial = ip:port), so the
        // existing 检测手机 → 启动特权直读 pair of buttons takes over from here.
        await DetectPhoneAsync();
    }

    /// <summary>
    /// Abandons whatever the wireless flow was doing: strands the QR wait (session bump),
    /// retires the QR, and returns the stage machine to Idle. Typed inputs survive unless a
    /// consent withdrawal asks for a full wipe.
    /// </summary>
    private void ResetWirelessFlow(bool clearInputs)
    {
        wirelessScanSession++;
        wirelessFlow.TryApply(WirelessPairingEvent.Cancelled);
        WirelessQrText = string.Empty;
        WirelessHint = string.Empty;
        if (clearInputs)
        {
            WirelessStatus = string.Empty;
            WirelessPairEndpointText = string.Empty;
            WirelessPairCodeText = string.Empty;
            WirelessConnectEndpointText = string.Empty;
            WirelessPanelOpen = false;
            // A consent withdrawal forgets the session memory too: no further probe may
            // keep talking about a wireless connection the user just walked away from.
            lastWirelessConnectEndpoint = null;
        }
    }

    /// <summary>Raised after a device is revoked so the app layer can drop its live sessions.</summary>
    public event Action<string>? DeviceRevoked;

    /// <summary>
    /// Raised when 图片同步 actually changed value. The app layer bounces live peer sessions:
    /// the wire version (v2 image frames vs text-only v1) is fixed when the phone dials, so
    /// without the bounce a session that predates the toggle keeps running the old version
    /// until some incidental disconnect — flipping the switch would look like it did nothing.
    /// </summary>
    public event Action? ImageSyncEnabledChanged;

    partial void OnImageSyncEnabledChanged(bool value) => ImageSyncEnabledChanged?.Invoke();

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

        if (int.TryParse(await store.GetSettingAsync("retention_max_entries"), out var maxEntries)
            && maxEntries is >= 100 and <= 2000)
        {
            RetentionMaxEntries = maxEntries;
        }

        HistoryFontScaleKey = ClipSync.App.Ui.HistoryDisplayOptions.ScaleKeyForStored(
            await store.GetSettingAsync("ui_history_font_scale"));
        PreviewLinesKey = ClipSync.App.Ui.HistoryDisplayOptions.LinesKeyForStored(
            await store.GetSettingAsync("ui_preview_lines"));
        ThemeModeKey = ClipSync.App.Ui.AppearanceOptions.KeyForStored(
            await store.GetSettingAsync("ui_theme"));
        LanguageKey = ClipSync.App.Ui.LanguageCatalog.KeyForStored(
            await store.GetSettingAsync("ui_language"));
        LaunchAtStartup = bool.TryParse(await store.GetSettingAsync("launch_at_startup"), out var launch) && launch;
        FlyoutHotkey = await store.GetSettingAsync("hotkey_flyout") ?? string.Empty;
        PauseHotkey = await store.GetSettingAsync("hotkey_pause") ?? string.Empty;
        BlockedProcesses = await store.GetSettingAsync("blocked_processes") ?? BlockedProcesses;
        AutoApplyRemote = !bool.TryParse(await store.GetSettingAsync("auto_apply_remote"), out var autoApply) || autoApply;
        // For image_sync and auto_apply_images alike, absent or unparseable resolves to the
        // on-default (same rule as auto_apply_remote); an explicit persisted "False" from a
        // user who opted out is honored.
        ImageSyncEnabled = !bool.TryParse(await store.GetSettingAsync("image_sync"), out var imageSync) || imageSync;
        AutoApplyImages = !bool.TryParse(await store.GetSettingAsync("auto_apply_images"), out var autoApplyImage) || autoApplyImage;
        ExtraBindAddresses = await store.GetSettingAsync("extra_bind_addresses") ?? string.Empty;
        BluetoothFallbackEnabled = bool.TryParse(await store.GetSettingAsync("bluetooth_fallback"), out var btFallback) && btFallback;
        PrivilegedAdbConsent = bool.TryParse(await store.GetSettingAsync("privileged_adb_consent"), out var adbConsent) && adbConsent;
        if (privilegedHost is not null)
        {
            // Locating adb is a file-system check, not an adb launch — safe before consent.
            PrivilegedAdbAvailable = privilegedHost.AdbAvailable;
            PrivilegedAdbLocation = privilegedHost.AdbLocationDescription;
        }
        PrivilegedStatus = !PrivilegedAdbConsent
            ? string.Empty
            : PrivilegedAdbAvailable
                ? Strings.Conduit_Privileged_TapDetect
                : Strings.Conduit_Privileged_AdbMissing;
        ApplySettings();
        await store.CleanupAsync(
            new ClipboardRetentionPolicy(
                maximumEntries: RetentionMaxEntries,
                maximumAge: TimeSpan.FromDays(RetentionDays)),
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
            ? Strings.Sync_StartFailed
            : connectedCount > 0
                ? Strings.Format(nameof(Strings.Sync_ConnectedFormat), port, connectedCount)
                : Strings.Format(nameof(Strings.Sync_WaitingFormat), port);
    }

    /// <summary>
    /// Applies a live Bluetooth-fallback snapshot to the conduit network segment. The four
    /// states are mutually exclusive: disabled, unavailable (adapter missing or radio off),
    /// armed and waiting, or carrying a session for a named device right now.
    /// </summary>
    public void UpdateBluetoothStatus(bool enabled, bool listening, string? connectedDeviceName, string? failureReason)
    {
        BluetoothSessionActive = enabled && connectedDeviceName is not null;
        BluetoothStatus = !enabled
            ? Strings.Bt_Disabled
            : failureReason is not null
                ? Strings.Format(nameof(Strings.Bt_UnavailableFormat), failureReason)
                : connectedDeviceName is not null
                    ? Strings.Format(nameof(Strings.Bt_SyncingFormat), connectedDeviceName)
                    : listening
                        ? Strings.Bt_Armed
                        : Strings.Bt_Starting;
    }

    /// <summary>Re-reads the outbox depth and last peer ack for the conduit local-service segment.</summary>
    public async Task RefreshOutboxAsync()
    {
        var status = await store.GetOutboxStatusAsync();
        OutboxPendingCount = status.PendingCount;
        LastAckText = status.LastPeerAckAt is { } ackedAt
            ? Strings.Format(
                nameof(Strings.Ack_UpToFormat),
                ackedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture))
            : Strings.Ack_None;
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

    /// <summary>Applies a format chip by key ("all", "link", "otp", "email", "credential", "plain").</summary>
    [RelayCommand]
    private async Task SetFormatFilterAsync(string? key)
    {
        FormatFilter = key switch
        {
            "link" => ClipContentFormat.Link,
            "otp" => ClipContentFormat.Otp,
            "email" => ClipContentFormat.Email,
            "credential" => ClipContentFormat.Credential,
            "plain" => ClipContentFormat.Plain,
            _ => null,
        };
        IsFormatFilterActive = FormatFilter is not null;
        await RefreshAsync();
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        var entries = await store.SearchAsync(new ClipboardHistoryQuery(SearchText));
        ActiveQuery = SearchText.Trim();
        History.Clear();
        foreach (var entry in entries)
        {
            var item = HistoryItemViewModel.FromEntry(entry, store.LocalDeviceId, LookupDevice, store.Media);
            // Format chips are text-shape filters (ADR 0003): images only show under 全部.
            if (FormatFilter is { } filter && (item.IsImage || item.Format != filter))
            {
                continue;
            }

            History.Add(item);
        }

        // The flyout always shows the newest clips regardless of the search box.
        RecentHistory.Clear();
        var recent = string.IsNullOrEmpty(SearchText)
            ? entries
            : await store.SearchAsync(new ClipboardHistoryQuery(string.Empty));
        foreach (var entry in recent.Take(RecentHistoryLength))
        {
            RecentHistory.Add(HistoryItemViewModel.FromEntry(entry, store.LocalDeviceId, LookupDevice, store.Media));
        }

        await RefreshOutboxAsync();
    }

    private PairedDeviceViewModel? LookupDevice(string deviceId) =>
        Devices.FirstOrDefault(device => device.DeviceId == deviceId);

    [RelayCommand]
    private async Task SearchAsync() => await RefreshAsync();

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

    /// <summary>Writes a stored image back to the clipboard as CF_DIB, suppressing re-capture.</summary>
    public void CopyImage(string contentHash)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(contentHash);
        var bytes = store.Media.ReadAllBytes(contentHash);
        // Suppress by pixel digest too: copying a stored JPEG echoes back from the listener
        // as a DIB→PNG re-encode whose content hash differs, and hash-only suppression would
        // record that echo as a duplicate history entry and sync it to the peer.
        capturePolicy.SuppressNextImage(
            contentHash,
            DateTimeOffset.UtcNow,
            ClipSync.App.Clipboard.ClipboardDataAccessor.TryPixelDigest(bytes));
        clipboardAdapter.WriteImage(bytes);
    }

    [RelayCommand(CanExecute = nameof(HasSelection))]
    private void CopySelected()
    {
        if (SelectedItem is null)
        {
            return;
        }

        CopyItem(SelectedItem);
    }

    /// <summary>Copies a specific clip (tray flyout cards) without touching the main-window selection.</summary>
    [RelayCommand]
    private void CopyItem(HistoryItemViewModel? item)
    {
        if (item is null)
        {
            return;
        }

        if (item.IsImage)
        {
            if (item.ContentHash is not null)
            {
                CopyImage(item.ContentHash);
            }

            return;
        }

        CopyText(item.Text);
    }

    /// <summary>
    /// Maps the current selection to the detail payload. Returns null when
    /// nothing is selected so the window layer can no-op without extra state.
    /// </summary>
    public ClipDetailPayload? GetSelectedDetail()
    {
        var item = SelectedItem;
        return item is null
            ? null
            : new ClipDetailPayload(
                item.Text,
                item.Source,
                item.CreatedAt,
                item.IsImage,
                item.MimeType,
                item.PixelWidth,
                item.PixelHeight,
                item.EncodedBytes,
                item.ContentHash,
                item.ThumbnailPath);
    }

    [RelayCommand(CanExecute = nameof(HasSelection))]
    private void ViewSelected() => DetailRequested?.Invoke();

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

    /// <summary>
    /// 清空历史（P0-5）: one-shot local deletion of every entry, image blobs included.
    /// Local-delete semantics — nothing is revoked on peers. Guarded by a two-step
    /// confirmation (injectable for tests) that recommends exporting first.
    /// </summary>
    [RelayCommand]
    private async Task ClearAsync()
    {
        if (!(clearHistoryConfirmer ?? ConfirmClearHistory)())
        {
            return;
        }

        var removed = await store.ClearAsync(DateTimeOffset.UtcNow);
        HistoryTransferStatus = Strings.Format(nameof(Strings.Transfer_ClearedFormat), removed);
        await RefreshAsync();
    }

    private static bool ConfirmClearHistory()
    {
        var first = System.Windows.MessageBox.Show(
            Strings.Transfer_ClearConfirmBody,
            Strings.App_Name,
            System.Windows.MessageBoxButton.OKCancel,
            System.Windows.MessageBoxImage.Warning);
        if (first != System.Windows.MessageBoxResult.OK)
        {
            return false;
        }

        return System.Windows.MessageBox.Show(
            Strings.Transfer_ClearConfirmAgain,
            Strings.App_Name,
            System.Windows.MessageBoxButton.OKCancel,
            System.Windows.MessageBoxImage.Warning) == System.Windows.MessageBoxResult.OK;
    }

    /// <summary>
    /// 导出历史: writes the whole clips table (live rows and deletion markers, text and
    /// image events with their blob bytes) as an export-format v1/v2 JSON Lines file.
    /// Events only — never pair secrets, certificates, or device rows. The status line
    /// states the plaintext nature honestly.
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
                HistoryTransferStatus = Strings.Format(nameof(Strings.Transfer_ExportedFormat), count, path);
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            HistoryTransferStatus = Strings.Transfer_ExportWriteFailed;
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

            HistoryTransferStatus = Strings.Format(
                nameof(Strings.Transfer_ImportedFormat), result.Imported, result.Skipped, result.Conflicts);
            await RefreshAsync();
        }
        catch (HistoryTransferException exception)
        {
            HistoryTransferStatus = Strings.Format(
                nameof(Strings.Transfer_ImportFailedFormat), DescribeTransferError(exception.ErrorCode));
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            HistoryTransferStatus = Strings.Transfer_ImportReadFailed;
        }
    }

    private static string DescribeTransferError(string errorCode) => errorCode switch
    {
        HistoryTransferErrorCodes.BadHeader => Strings.Transfer_Error_BadHeader,
        HistoryTransferErrorCodes.UnsupportedVersion => Strings.Transfer_Error_UnsupportedVersion,
        HistoryTransferErrorCodes.MalformedRecord => Strings.Transfer_Error_MalformedRecord,
        HistoryTransferErrorCodes.HashMismatch => Strings.Transfer_Error_HashMismatch,
        HistoryTransferErrorCodes.CountMismatch => Strings.Transfer_Error_CountMismatch,
        HistoryTransferErrorCodes.ContentTooLarge => Strings.Transfer_Error_ContentTooLarge,
        _ => Strings.Transfer_Error_Unknown
    };

    private static string? PickExportPath()
    {
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Title = Strings.Common_ExportHistory,
            FileName = $"clipsync-history-{DateTime.Now:yyyyMMdd-HHmmss}.jsonl",
            DefaultExt = HistoryExportFormat.SuggestedExtension,
            Filter = Strings.Transfer_FileFilter
        };
        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }

    private static string? PickImportPath()
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = Strings.Common_ImportHistory,
            DefaultExt = HistoryExportFormat.SuggestedExtension,
            Filter = Strings.Transfer_FileFilter
        };
        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }

    [RelayCommand]
    private async Task SaveSettingsAsync()
    {
        RetentionDays = Math.Clamp(RetentionDays, 1, 3650);
        RetentionMaxEntries = Math.Clamp(RetentionMaxEntries, 100, 2000);
        await store.SetSettingAsync("is_paused", IsPaused.ToString());
        await store.SetSettingAsync("is_private_mode", IsPrivateMode.ToString());
        await store.SetSettingAsync("retention_days", RetentionDays.ToString(System.Globalization.CultureInfo.InvariantCulture));
        await store.SetSettingAsync("retention_max_entries", RetentionMaxEntries.ToString(System.Globalization.CultureInfo.InvariantCulture));
        await store.SetSettingAsync("ui_history_font_scale", ClipSync.App.Ui.HistoryDisplayOptions.StoredScaleFor(HistoryFontScaleKey));
        await store.SetSettingAsync("ui_preview_lines", ClipSync.App.Ui.HistoryDisplayOptions.StoredLinesFor(PreviewLinesKey));
        await store.SetSettingAsync("ui_theme", ClipSync.App.Ui.AppearanceOptions.StoredFor(ThemeModeKey));
        await store.SetSettingAsync("ui_language", ClipSync.App.Ui.LanguageCatalog.StoredFor(LanguageKey));
        await store.SetSettingAsync("launch_at_startup", LaunchAtStartup.ToString());
        await store.SetSettingAsync("hotkey_flyout", FlyoutHotkey);
        await store.SetSettingAsync("hotkey_pause", PauseHotkey);
        await store.SetSettingAsync("blocked_processes", BlockedProcesses);
        await store.SetSettingAsync("auto_apply_remote", AutoApplyRemote.ToString());
        await store.SetSettingAsync("image_sync", ImageSyncEnabled.ToString());
        await store.SetSettingAsync("auto_apply_images", AutoApplyImages.ToString());
        await store.SetSettingAsync("extra_bind_addresses", ExtraBindAddresses);
        await store.SetSettingAsync("bluetooth_fallback", BluetoothFallbackEnabled.ToString());
        ApplySettings();
        await store.CleanupAsync(
            new ClipboardRetentionPolicy(
                maximumEntries: RetentionMaxEntries,
                maximumAge: TimeSpan.FromDays(RetentionDays)),
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
        var backlog = await store.GetOutboxDepthByPeerAsync();
        var now = DateTimeOffset.UtcNow;
        Devices.Clear();
        var staleCount = 0;
        var staleBacklog = 0;
        for (var position = 0; position < devices.Count; position++)
        {
            var device = devices[position];
            var pending = backlog.GetValueOrDefault(device.DeviceId);
            var staleReason = DescribeStaleness(device, devices, now);
            if (staleReason is not null)
            {
                staleCount++;
                staleBacklog += pending;
                if (pending > 0)
                {
                    staleReason = Strings.Format(nameof(Strings.Stale_BacklogSuffixFormat), staleReason, pending);
                }
            }

            Devices.Add(PairedDeviceViewModel.FromDevice(device, position, pending, staleReason));
        }

        SelectedDevice = Devices.FirstOrDefault(device => device.DeviceId == selectedId);
        HasPairedDevices = Devices.Any(device => !device.IsRevoked);
        StaleDeviceCount = staleCount;
        StaleBannerText = staleCount == 0
            ? string.Empty
            : staleBacklog > 0
                ? Strings.Format(nameof(Strings.Stale_BannerBacklogFormat), staleCount, staleBacklog)
                : Strings.Format(nameof(Strings.Stale_BannerFormat), staleCount);
    }

    /// <summary>
    /// The charter treats re-pairing the same phone as replacing its old record, so an active
    /// device is a leftover when a same-named, same-platform sibling has been active more
    /// recently (the "two rows both named Xiaomi 22041216C" case), or when it simply has not
    /// connected for <see cref="StaleAfterDays"/> days. Returns the badge text, or null for
    /// healthy devices. Revoked rows are already grey facts and are never flagged.
    /// </summary>
    private static string? DescribeStaleness(
        PairedDevice device,
        IReadOnlyList<PairedDevice> devices,
        DateTimeOffset now)
    {
        if (device.IsRevoked)
        {
            return null;
        }

        var hasFresherTwin = devices.Any(other => !other.IsRevoked
            && !string.Equals(other.DeviceId, device.DeviceId, StringComparison.Ordinal)
            && string.Equals(other.DisplayName, device.DisplayName, StringComparison.Ordinal)
            && string.Equals(other.Platform, device.Platform, StringComparison.Ordinal)
            && IsFresher(other, device));
        if (hasFresherTwin)
        {
            return Strings.Stale_DuplicateBadge;
        }

        var lastActivity = device.LastSeenAt ?? device.CreatedAt;
        if (now - lastActivity > TimeSpan.FromDays(StaleAfterDays))
        {
            return device.LastSeenAt is null
                ? Strings.Stale_NeverConnected
                : Strings.Format(nameof(Strings.Stale_UnseenDaysFormat), StaleAfterDays);
        }

        return null;
    }

    /// <summary>Deterministic freshness order: last activity, then pairing time, then device id.</summary>
    private static bool IsFresher(PairedDevice left, PairedDevice right)
    {
        var leftSeen = (left.LastSeenAt ?? left.CreatedAt).ToUnixTimeMilliseconds();
        var rightSeen = (right.LastSeenAt ?? right.CreatedAt).ToUnixTimeMilliseconds();
        if (leftSeen != rightSeen)
        {
            return leftSeen > rightSeen;
        }

        if (left.CreatedAt != right.CreatedAt)
        {
            return left.CreatedAt > right.CreatedAt;
        }

        return string.CompareOrdinal(left.DeviceId, right.DeviceId) > 0;
    }

    /// <summary>
    /// One tap: revokes every flagged leftover. The store clears each secret, bumps the trust
    /// epoch, and drops the peer's outbox rows in the same transaction, so the 待发 count
    /// deflates immediately; DeviceRevoked lets the app layer kick any live session.
    /// </summary>
    [RelayCommand(CanExecute = nameof(HasStaleDevices))]
    private async Task CleanupStaleDevicesAsync()
    {
        foreach (var device in Devices.Where(device => device.IsStale).ToArray())
        {
            if (await store.RevokeDeviceAsync(device.DeviceId, DateTimeOffset.UtcNow))
            {
                DeviceRevoked?.Invoke(device.DeviceId);
            }
        }

        await RefreshDevicesAsync();
        await RefreshOutboxAsync();
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

    /// <summary>
    /// 偏好 · 关于: compare this portable copy to GitHub <c>/releases/latest</c>.
    /// Checking never downloads; a newer ZIP is offered as a separate action.
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanCheckForUpdates))]
    private async Task CheckForUpdatesAsync()
    {
        UpdateBusy = true;
        UpdateAvailable = false;
        pendingUpdate = null;
        UpdateStatus = Strings.Prefs_Update_Checking;
        try
        {
            var result = await updates.CheckAsync();
            pendingUpdate = result;
            if (result.Payload is null)
            {
                UpdateStatus = Strings.Prefs_Update_Error_NoAsset;
                return;
            }

            if (result.UpdateAvailable)
            {
                UpdateAvailable = true;
                UpdateStatus = Strings.Format(
                    nameof(Strings.Prefs_Update_AvailableFormat),
                    result.Latest.VersionLabel,
                    result.CurrentVersion);
            }
            else
            {
                UpdateStatus = Strings.Format(
                    nameof(Strings.Prefs_Update_UpToDateFormat),
                    result.CurrentVersion);
            }
        }
        catch (HttpRequestException)
        {
            UpdateStatus = Strings.Prefs_Update_Error_Network;
        }
        catch (FormatException)
        {
            UpdateStatus = Strings.Prefs_Update_Error_Parse;
        }
        catch (Exception)
        {
            UpdateStatus = Strings.Prefs_Update_Error_Network;
        }
        finally
        {
            UpdateBusy = false;
        }
    }

    private bool CanCheckForUpdates() => !UpdateBusy;

    [RelayCommand(CanExecute = nameof(CanDownloadUpdate))]
    private async Task DownloadUpdateAsync()
    {
        var check = pendingUpdate;
        if (check is null || !check.UpdateAvailable || check.Payload is null)
        {
            return;
        }

        UpdateBusy = true;
        var progress = new Progress<UpdateDownloadProgress>(p =>
            UpdateStatus = Strings.Format(nameof(Strings.Prefs_Update_DownloadingFormat), p.Percent));
        try
        {
            UpdateStatus = Strings.Format(nameof(Strings.Prefs_Update_DownloadingFormat), 0);
            var script = await updates.PrepareApplyAsync(check, progress);
            UpdateStatus = Strings.Prefs_Update_Restarting;
            UpdateReadyToApply?.Invoke(script);
        }
        catch (HttpRequestException)
        {
            UpdateStatus = Strings.Prefs_Update_Error_Network;
        }
        catch (InvalidOperationException exception) when (
            exception.Message.Contains("SHA-256", StringComparison.Ordinal))
        {
            UpdateStatus = Strings.Prefs_Update_Error_Hash;
        }
        catch (Exception)
        {
            UpdateStatus = Strings.Prefs_Update_Error_Apply;
        }
        finally
        {
            UpdateBusy = false;
        }
    }

    private bool CanDownloadUpdate() => UpdateAvailable && !UpdateBusy && pendingUpdate?.Payload is not null;

    /// <summary>
    /// 设备色手动改（P1#14）: a swatch tap on a conduit device row. Choosing the
    /// pairing-order default stores null (back to 跟随配对顺位). History reloads too,
    /// because source-tag tinting reads the device's effective accent.
    /// </summary>
    [RelayCommand]
    private async Task SetDeviceAccentAsync(DeviceAccentSwatch? swatch)
    {
        if (swatch is null)
        {
            return;
        }

        await store.SetDeviceAccentAsync(swatch.DeviceId, swatch.OverrideToStore);
        await RefreshDevicesAsync();
        await RefreshAsync();
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

    private void ApplySettings()
    {
        var blocked = BlockedProcesses
            .Split([',', ';', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        capturePolicy.UpdateSettings(new CaptureSettings(
            IsPaused,
            IsPrivateMode,
            blocked,
            TimeSpan.FromDays(RetentionDays),
            ImageSyncEnabled));
    }
}
