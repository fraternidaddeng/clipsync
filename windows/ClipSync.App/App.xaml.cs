using ClipSync.App.Clipboard;
using ClipSync.App.Diagnostics;
using ClipSync.App.Localization;
using ClipSync.App.Onboarding;
using ClipSync.App.Pairing;
using ClipSync.App.PrivilegedHost;
using ClipSync.App.Security;
using ClipSync.App.Startup;
using ClipSync.App.Sync;
using ClipSync.App.Theme;
using ClipSync.App.Tray;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Clipboard.PrivilegedHost;
using ClipSync.Core.Onboarding;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Peer.Bluetooth;
using ClipSync.Peer.Pairing;
using ClipSync.Peer.Sessions;
using Microsoft.Extensions.DependencyInjection;
using System.Collections.Specialized;
using System.ComponentModel;
using System.IO;
using System.Windows;

namespace ClipSync.App;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "WPF Application lifetime: every owned resource is disposed in OnExit.")]
public partial class App : Application
{
    /// <summary>Covers slow-moving state without events: peer acks, device last-seen, missed session changes.</summary>
    private static readonly TimeSpan LiveRefreshInterval = TimeSpan.FromSeconds(30);

    private ServiceProvider? services;
    private TrayIconController? trayIcon;
    private TrayFlyoutWindow? trayFlyout;
    private MainViewModel? mainViewModel;
    private Win32ClipboardAdapter? clipboardAdapter;
    private PeerSyncHost? syncHost;
    private BluetoothSyncHost? bluetoothHost;
    private readonly SemaphoreSlim bluetoothGate = new(1, 1);
    private PairingService? pairingService;
    private PairingQrWindow? pairingWindow;
    private FirstRunStore? firstRunStore;
    private OnboardingWindow? onboardingWindow;
    private GlobalHotkeyManager? hotkeyManager;
    private System.Windows.Threading.DispatcherTimer? liveRefreshTimer;
    private bool peerEndpointUnavailable;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Tray-only contract: when a console-subsystem launcher (e.g. `dotnet
        // ClipSync.App.dll`) hosted this process, an empty console window would otherwise
        // sit on screen for the whole session. Detach it before anything becomes visible.
        if (ConsoleWindowGuard.DetachInheritedConsole())
        {
            LocalDiagnostics.Write("console_detached");
        }

        // Pick day/night tokens before any window loads, then follow Windows theme changes.
        CharterThemeManager.Initialize();

        var dataDirectory = Environment.GetEnvironmentVariable("CLIPSYNC_DATA_DIR");
        if (string.IsNullOrWhiteSpace(dataDirectory))
        {
            dataDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ClipSync");
        }
        Directory.CreateDirectory(dataDirectory);

        var deviceId = LocalDeviceIdentity.GetOrCreate(dataDirectory);
        var collection = new ServiceCollection();
        collection.AddSingleton(new SqliteClipboardEventStore(
            Path.Combine(dataDirectory, "clipsync.db"),
            deviceId));
        collection.AddSingleton<IClipboardEventStore>(provider => provider.GetRequiredService<SqliteClipboardEventStore>());
        collection.AddSingleton<ISecretProtector, DpapiSecretProtector>();
        collection.AddSingleton<ClipboardCapturePolicy>();
        collection.AddSingleton<ClipboardCaptureService>();
        collection.AddSingleton<Win32ClipboardAdapter>();
        // 特权直读 · PC 侧 adb 协助（任务 1）: locate adb once at startup (a file check, not a
        // launch); the assistant issues real adb calls only from explicit, consented actions.
        collection.AddSingleton<IAdbRunner>(_ => new ProcessAdbRunner());
        collection.AddSingleton<PrivilegedHostAssistant>();
        collection.AddSingleton<MainViewModel>();
        collection.AddSingleton<MainWindow>();
        services = collection.BuildServiceProvider();

        var store = services.GetRequiredService<SqliteClipboardEventStore>();
        await store.InitializeAsync();
        // 语言（P1#16）: the stored override must become the process UI culture before the
        // first view model or window constructs — status lines, tray strings and every
        // {x:Static} XAML lookup resolve against it. 跟随系统 leaves the OS account
        // language in charge. Changing the picker later only persists; restart applies.
        LocalizationManager.ApplyLanguage(await store.GetSettingAsync("ui_language"));
        var viewModel = services.GetRequiredService<MainViewModel>();
        await viewModel.InitializeAsync();
        // 外观（P1-6）: a stored 日间/夜间 override must pin the palette before any window
        // shows; 跟随系统 keeps the theme Initialize() already applied. Tray icons keep
        // sampling the taskbar theme either way.
        CharterThemeManager.SetMode(viewModel.ThemeModeKey);
        // 历史字号/预览行数 must be in the resource dictionary before any window measures.
        HistoryTypeScaleManager.Apply(viewModel.HistoryFontScale, viewModel.PreviewLines);
        var mainWindow = services.GetRequiredService<MainWindow>();
        MainWindow = mainWindow;
        trayFlyout = new TrayFlyoutWindow(viewModel);
        trayIcon = TrayIconController.Create(mainWindow, Shutdown, () => trayFlyout?.ShowFlyout());
        mainViewModel = viewModel;
        viewModel.PropertyChanged += OnViewModelPropertyChanged;
        viewModel.Devices.CollectionChanged += OnDevicesChanged;
        UpdateTrayState();

        // 开机自启（P0-3）: re-assert the per-user Run entry so a moved executable heals
        // itself; the global hotkeys (P1-9) register before any window shows so they work
        // from a tray-only start too. A `--minimized` launch (the autostart path) stays
        // in the tray; a manual launch opens the main window.
        ReconcileLaunchAtStartup(viewModel.LaunchAtStartup);
        hotkeyManager = new GlobalHotkeyManager();
        hotkeyManager.Pressed += OnGlobalHotkeyPressed;
        ApplyGlobalHotkeys();
        if (!StartupRegistration.IsMinimizedLaunch(e.Args))
        {
            mainWindow.Show();
        }
        else
        {
            LocalDiagnostics.Write("started_minimized");
        }

        clipboardAdapter = services.GetRequiredService<Win32ClipboardAdapter>();
        clipboardAdapter.TextChanged += OnClipboardTextChanged;
        clipboardAdapter.Faulted += OnClipboardFaulted;
        clipboardAdapter.Start();
        LocalDiagnostics.Write("listener_started");

        await StartPeerEndpointAsync(dataDirectory, deviceId, store, viewModel);
        await SyncBluetoothHostAsync();
        UpdateTrayState();

        liveRefreshTimer = new System.Windows.Threading.DispatcherTimer { Interval = LiveRefreshInterval };
        liveRefreshTimer.Tick += OnLiveRefreshTick;
        liveRefreshTimer.Start();

        // 首开引导（对齐 Android 五步教程）：只对尚未配对的新安装展示一次；已配对的
        // 安装静默标记已读、不打扰。自启入托盘（--minimized）的安静路径不弹窗，也不
        // 消费首开资格——下次手动打开再展示。放在启动尾声：对端服务已起，配对步能
        // 出示真实二维码。ShowDialog 阻塞在 OnStartup 末尾是刻意的——一切都已就绪。
        firstRunStore = new FirstRunStore(store);
        if (!StartupRegistration.IsMinimizedLaunch(e.Args)
            && await firstRunStore.ShouldShowOnboardingAsync(viewModel.HasPairedDevices))
        {
            LocalDiagnostics.Write("onboarding_shown");
            ShowOnboardingWindow(mainWindow);
        }
    }

    /// <summary>
    /// Opens (or focuses) the first-open tutorial. Also the 偏好 · 帮助 · 重新查看引导 replay
    /// entry — replaying never touches settings or pairings, it only walks the same five steps.
    /// </summary>
    public void ShowOnboardingWindow(Window owner)
    {
        if (onboardingWindow is not null)
        {
            onboardingWindow.Activate();
            return;
        }

        onboardingWindow = new OnboardingWindow(
            pairingService,
            syncHost,
            markSeen: () => _ = MarkOnboardingSeenAsync(),
            openConduit: () => (MainWindow as MainWindow)?.FocusConduitPage())
        {
            Owner = owner
        };
        onboardingWindow.Closed += (_, _) => onboardingWindow = null;
        onboardingWindow.ShowDialog();
    }

    /// <summary>Persists the seen flag; a storage failure only means the tutorial may show again.</summary>
    private async Task MarkOnboardingSeenAsync()
    {
        if (firstRunStore is null)
        {
            return;
        }

        try
        {
            await firstRunStore.MarkOnboardingSeenAsync();
        }
        catch (Exception exception)
        {
            LocalDiagnostics.Write($"onboarding_mark_seen_failed_{exception.GetType().Name}");
        }
    }

    /// <summary>
    /// Periodic fallback for state that has no push event: outbox acks drain, device
    /// last-seen times move, and the connected-session count is re-polled defensively.
    /// </summary>
    private async void OnLiveRefreshTick(object? sender, EventArgs e)
    {
        if (mainViewModel is null)
        {
            return;
        }

        try
        {
            if (syncHost is { IsRunning: true })
            {
                mainViewModel.UpdatePeerStatus(true, syncHost.Port, syncHost.ConnectedDeviceCount);
            }

            await mainViewModel.RefreshOutboxAsync();
            await mainViewModel.RefreshDevicesCommand.ExecuteAsync(null);
        }
        catch (Exception exception)
        {
            LocalDiagnostics.Write($"live_refresh_failed_{exception.GetType().Name}");
        }
    }

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(MainViewModel.IsPaused)
            or nameof(MainViewModel.IsPrivateMode)
            or nameof(MainViewModel.CaptureFaulted))
        {
            UpdateTrayState();
        }

        if (e.PropertyName is nameof(MainViewModel.BluetoothFallbackEnabled))
        {
            _ = SyncBluetoothHostAsync();
        }

        if (e.PropertyName is nameof(MainViewModel.HistoryFontScaleKey)
            or nameof(MainViewModel.PreviewLinesKey))
        {
            if (mainViewModel is not null)
            {
                HistoryTypeScaleManager.Apply(mainViewModel.HistoryFontScale, mainViewModel.PreviewLines);
            }
        }

        // 外观 chips apply live: every open window restyles through DynamicResource at once.
        if (e.PropertyName is nameof(MainViewModel.ThemeModeKey) && mainViewModel is not null)
        {
            CharterThemeManager.SetMode(mainViewModel.ThemeModeKey);
        }

        if (e.PropertyName is nameof(MainViewModel.LaunchAtStartup) && mainViewModel is not null)
        {
            ReconcileLaunchAtStartup(mainViewModel.LaunchAtStartup);
        }

        if (e.PropertyName is nameof(MainViewModel.FlyoutHotkey) or nameof(MainViewModel.PauseHotkey))
        {
            ApplyGlobalHotkeys();
        }
    }

    /// <summary>
    /// Writes or removes the per-user Run entry to match the 开机自启 intent. A registry
    /// failure is recorded and shown as a fact — the toggle keeps the user's intent.
    /// </summary>
    private static void ReconcileLaunchAtStartup(bool enabled)
    {
        try
        {
            StartupRegistration.SetEnabled(enabled);
        }
        catch (Exception exception) when (exception is System.Security.SecurityException
            or UnauthorizedAccessException
            or IOException
            or InvalidOperationException)
        {
            LocalDiagnostics.Write($"startup_registration_failed_{exception.GetType().Name}");
        }
    }

    /// <summary>
    /// Reconciles both global hotkeys（呼出浮窗 / 暂停同步）with their settings and states
    /// the outcome on each preferences row. Both are re-applied together so that editing
    /// one chord frees or claims the OS registration deterministically: on an identical
    /// chord the flyout hotkey wins and the pause row states the in-app collision honestly
    /// instead of blaming "another program".
    /// </summary>
    private void ApplyGlobalHotkeys()
    {
        if (mainViewModel is null || hotkeyManager is null)
        {
            return;
        }

        var pauseGesture = mainViewModel.PauseHotkey;
        _ = hotkeyManager.TryApply(GlobalHotkey.PauseSync, null);

        var flyoutGesture = mainViewModel.FlyoutHotkey;
        var flyoutApplied = hotkeyManager.TryApply(GlobalHotkey.Flyout, flyoutGesture);
        mainViewModel.FlyoutHotkeyConflict = !flyoutApplied;
        mainViewModel.FlyoutHotkeyStatus =
            flyoutGesture.Length == 0 ? string.Empty
            : flyoutApplied ? Strings.Hotkey_Applied
            : Strings.Hotkey_Conflict;
        if (!flyoutApplied)
        {
            LocalDiagnostics.Write("flyout_hotkey_unavailable");
        }

        if (pauseGesture.Length > 0 && pauseGesture == flyoutGesture)
        {
            mainViewModel.PauseHotkeyConflict = true;
            mainViewModel.PauseHotkeyStatus = Strings.Hotkey_ConflictSelf;
            LocalDiagnostics.Write("pause_hotkey_self_conflict");
            return;
        }

        var pauseApplied = hotkeyManager.TryApply(GlobalHotkey.PauseSync, pauseGesture);
        mainViewModel.PauseHotkeyConflict = !pauseApplied;
        mainViewModel.PauseHotkeyStatus =
            pauseGesture.Length == 0 ? string.Empty
            : pauseApplied ? Strings.Hotkey_PauseApplied
            : Strings.Hotkey_Conflict;
        if (!pauseApplied)
        {
            LocalDiagnostics.Write("pause_hotkey_unavailable");
        }
    }

    private void OnGlobalHotkeyPressed(GlobalHotkey hotkey)
    {
        if (hotkey == GlobalHotkey.Flyout)
        {
            trayFlyout?.ShowFlyout();
            return;
        }

        TogglePauseFromHotkey();
    }

    /// <summary>
    /// 暂停同步快捷键: same act as the flyout's pause button — flip the intent and persist
    /// it. Every surface (tray colour, flyout, conduit page) follows via property change.
    /// </summary>
    private async void TogglePauseFromHotkey()
    {
        if (mainViewModel is null)
        {
            return;
        }

        mainViewModel.IsPaused = !mainViewModel.IsPaused;
        await mainViewModel.SaveSettingsFromUiAsync();
    }

    private void OnDevicesChanged(object? sender, NotifyCollectionChangedEventArgs e) => UpdateTrayState();

    /// <summary>
    /// Recomputes the tray state from the current app state. Priority (see
    /// <see cref="TrayStateMapper"/>): private &gt; paused &gt; attention &gt; flow.
    /// Attention covers the "needs your action" cases detectable on the PC side:
    /// the peer endpoint failed to start, or no usable device is paired yet.
    /// </summary>
    private void UpdateTrayState()
    {
        if (trayIcon is null || mainViewModel is null)
        {
            return;
        }

        var hasUsableDevice = mainViewModel.Devices.Any(device => !device.IsRevoked);
        var attentionReason =
            peerEndpointUnavailable ? Strings.Attention_PeerDown
            : mainViewModel.CaptureFaulted ? Strings.Attention_CaptureFaulted
            : !hasUsableDevice ? Strings.Attention_NoDevice
            : null;
        var state = TrayStateMapper.Map(mainViewModel.IsPrivateMode, mainViewModel.IsPaused, attentionReason is not null);
        trayIcon.SetState(state, attentionReason);
    }

    private async Task StartPeerEndpointAsync(
        string dataDirectory,
        string deviceId,
        SqliteClipboardEventStore store,
        MainViewModel viewModel)
    {
        try
        {
            var protector = services!.GetRequiredService<ISecretProtector>();
            var certificate = PeerCertificateProvider.GetOrCreate(dataDirectory, deviceId, protector);
            pairingService = new PairingService(
                store,
                protector,
                new WpfPairingApprover(Dispatcher),
                new PairingServiceOptions { LocalDisplayName = LocalDisplayName() });
            pairingService.PairingCompleted += OnPairingCompleted;
            pairingService.PeersSuperseded += OnPeersSuperseded;
            viewModel.DeviceRevoked += OnDeviceRevoked;
            // 图片同步 flips must reach live sessions: the wire version is fixed at dial time,
            // so bounce every session and let the phone redial (~1 s) on the right version.
            viewModel.ImageSyncEnabledChanged += OnImageSyncEnabledChanged;
            // 一键暂停/私密模式 must stop outbound content immediately, not only capture:
            // the gate is re-read inside every session, so the tray toggle applies live.
            syncHost = new PeerSyncHost(
                store,
                protector,
                certificate,
                pairingService,
                outboundAllowed: () => !viewModel.IsPaused && !viewModel.IsPrivateMode,
                // 图片同步 governs inbound acceptance too, not only capture: while off, the
                // /v2 route is refused and live sessions take no image bodies (audit §3 P1).
                imageSyncEnabled: () => viewModel.ImageSyncEnabled,
                // Health-endpoint self-report: the phone's 对端写入 segment reads this instead
                // of sitting on 未探测 while sync visibly works. Posture + real-apply evidence.
                clipboardApplyState: () => viewModel.ClipboardApplyState);
            syncHost.RemoteClipsCommitted += OnRemoteClipsCommitted;
            syncHost.LocalOnlyMarksChanged += OnLocalOnlyMarksChanged;
            syncHost.SessionsChanged += OnPeerSessionsChanged;
            syncHost.DeviceLockedOut += OnDeviceLockedOut;
            syncHost.PeerStatusChanged += OnPeerStatusChanged;
            await syncHost.StartAsync(viewModel.ExtraBindAddresses);
            viewModel.LocalFingerprint = FormatFingerprint(syncHost.CertificateFingerprint);
            viewModel.UpdatePeerStatus(true, syncHost.Port, syncHost.ConnectedDeviceCount);
        }
        catch (Exception exception)
        {
            LocalDiagnostics.Write($"peer_start_failed_{exception.GetType().Name}");
            viewModel.UpdatePeerStatus(false, 0, 0);
            peerEndpointUnavailable = true;
        }
    }

    /// <summary>
    /// Reconciles the Bluetooth fallback host with the 蓝牙备援 toggle (ADR 0005): starts
    /// the RFCOMM listener when the setting turned on, tears it down when it turned off.
    /// A start failure (adapter missing, radio off) surfaces on the conduit page instead
    /// of pretending the fallback is armed; flipping the toggle retries.
    /// </summary>
    private async Task SyncBluetoothHostAsync()
    {
        if (mainViewModel is null || services is null)
        {
            return;
        }

        await bluetoothGate.WaitAsync();
        try
        {
            var enabled = mainViewModel.BluetoothFallbackEnabled;
            if (!enabled)
            {
                if (bluetoothHost is not null)
                {
                    var stopping = bluetoothHost;
                    bluetoothHost = null;
                    DetachBluetoothHost(stopping);
                    await stopping.DisposeAsync();
                    LocalDiagnostics.Write("bluetooth_fallback_stopped");
                }

                mainViewModel.UpdateBluetoothStatus(false, false, null, null);
                return;
            }

            if (bluetoothHost is not null)
            {
                UpdateBluetoothStatusFromHost();
                return;
            }

            var store = services.GetRequiredService<SqliteClipboardEventStore>();
            var protector = services.GetRequiredService<ISecretProtector>();
            var viewModel = mainViewModel;
            var host = new BluetoothSyncHost(
                store,
                protector,
                new RfcommServer(),
                new BluetoothSyncHostOptions
                {
                    SessionOptions = new SyncSessionOptions
                    {
                        ClientVersion = typeof(App).Assembly.GetName().Version?.ToString(3) ?? "0.2.0",
                        Platform = "windows",
                        // The same pause/private gate as the IP path: outbound content
                        // stops immediately on either transport.
                        OutboundAllowed = () => !viewModel.IsPaused && !viewModel.IsPrivateMode
                    }
                });
            host.RemoteClipsCommitted += OnRemoteClipsCommitted;
            host.LocalOnlyMarksChanged += OnLocalOnlyMarksChanged;
            host.SessionsChanged += OnBluetoothSessionsChanged;
            host.DeviceLockedOut += OnDeviceLockedOut;
            try
            {
                await host.StartAsync();
                bluetoothHost = host;
                mainViewModel.UpdateBluetoothStatus(true, true, null, null);
                LocalDiagnostics.Write("bluetooth_fallback_started");
            }
            catch (Exception exception)
            {
                DetachBluetoothHost(host);
                await host.DisposeAsync();
                mainViewModel.UpdateBluetoothStatus(true, false, null, Strings.Bt_AdapterUnavailable);
                LocalDiagnostics.Write($"bluetooth_start_failed_{exception.GetType().Name}");
            }
        }
        finally
        {
            bluetoothGate.Release();
        }
    }

    private void DetachBluetoothHost(BluetoothSyncHost host)
    {
        host.RemoteClipsCommitted -= OnRemoteClipsCommitted;
        host.LocalOnlyMarksChanged -= OnLocalOnlyMarksChanged;
        host.SessionsChanged -= OnBluetoothSessionsChanged;
        host.DeviceLockedOut -= OnDeviceLockedOut;
    }

    /// <summary>The Bluetooth session authenticated or ended (worker thread); refresh the conduit row.</summary>
    private void OnBluetoothSessionsChanged() => _ = Dispatcher.InvokeAsync(UpdateBluetoothStatusFromHost);

    private void UpdateBluetoothStatusFromHost()
    {
        if (mainViewModel is null || bluetoothHost is not { } host)
        {
            return;
        }

        var deviceId = host.ConnectedDeviceId;
        var deviceName = deviceId is null
            ? null
            : mainViewModel.Devices.FirstOrDefault(device => device.DeviceId == deviceId)?.DisplayName
                ?? Strings.Device_PairedFallback;
        mainViewModel.UpdateBluetoothStatus(true, host.IsListening, deviceName, null);
    }

    /// <summary>
    /// A session authenticated or ended (worker thread). Push the new connected count into
    /// the conduit page and refresh the device list: authentication just bumped last-seen.
    /// </summary>
    private void OnPeerSessionsChanged()
    {
        _ = Dispatcher.InvokeAsync(async () =>
        {
            if (mainViewModel is null || syncHost is not { IsRunning: true } host)
            {
                return;
            }

            mainViewModel.UpdatePeerStatus(true, host.Port, host.ConnectedDeviceCount);
            await mainViewModel.RefreshDevicesCommand.ExecuteAsync(null);
        });
    }

    /// <summary>
    /// A resume/network recovery pass changed the endpoint's state (worker thread): push the
    /// fresh online flag, port, and connected count into the conduit page and tray flyout.
    /// A failed rebind reads as offline here until a later recovery pass brings it back.
    /// </summary>
    private void OnPeerStatusChanged()
    {
        _ = Dispatcher.InvokeAsync(() =>
        {
            if (mainViewModel is null || syncHost is null)
            {
                return;
            }

            mainViewModel.UpdatePeerStatus(syncHost.IsRunning, syncHost.Port, syncHost.ConnectedDeviceCount);
        });
    }

    /// <summary>Groups the hex fingerprint by four, eight groups per line — humans compare groups, not character streams.</summary>
    private static string FormatFingerprint(string hex)
    {
        var upper = hex.ToUpperInvariant();
        var groups = Enumerable.Range(0, upper.Length / 4)
            .Select(i => upper.Substring(i * 4, 4));
        return string.Join('\n', groups.Chunk(8).Select(line => string.Join(' ', line)));
    }

    /// <summary>PairingJson caps display names at 64 characters; machine names fit far below that.</summary>
    private static string LocalDisplayName()
    {
        var name = Environment.MachineName.Trim();
        return name.Length is >= 1 and <= 64 ? name : "Windows PC";
    }

    /// <summary>Opens (or focuses) the QR pairing window; requires the peer endpoint to be up.</summary>
    public void ShowPairingWindow(Window owner)
    {
        if (syncHost is not { IsRunning: true } || pairingService is null)
        {
            MessageBox.Show(
                owner,
                Strings.Pairing_PeerDownBody,
                Strings.App_Name,
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            return;
        }

        if (pairingWindow is not null)
        {
            pairingWindow.Activate();
            return;
        }

        pairingWindow = new PairingQrWindow(pairingService, syncHost) { Owner = owner };
        pairingWindow.Closed += (_, _) => pairingWindow = null;
        pairingWindow.Show();
    }

    private void OnPairingCompleted(PairedDevice device)
    {
        _ = Dispatcher.InvokeAsync(async () =>
        {
            if (services is not null)
            {
                var viewModel = services.GetRequiredService<MainViewModel>();
                await viewModel.RefreshDevicesCommand.ExecuteAsync(null);
                // 特权直读（任务 1）: right after pairing, if the user already allowed adb, refresh
                // the card so a one-click 启动特权直读 is waiting when the phone is plugged in.
                // No adb call runs unless consent was given — the command self-guards.
                if (viewModel.PrivilegedAdbConsent)
                {
                    await viewModel.DetectPhoneCommand.ExecuteAsync(null);
                }
            }
        });
    }

    private void OnDeviceRevoked(string deviceId) => syncHost?.DisconnectDevice(deviceId);

    /// <summary>
    /// 图片同步 changed: drop live sessions so the paired phone redials and the handshake
    /// renegotiates the wire version under the new setting (v2 with image frames when both
    /// sides allow it, text-only v1 otherwise). Without this a pre-toggle session would keep
    /// its dial-time version until an incidental disconnect that may be hours away.
    /// </summary>
    private void OnImageSyncEnabledChanged() => syncHost?.DisconnectAllSessions();

    /// <summary>
    /// A confirmed pairing superseded stale same-name records (same phone, fresh device id):
    /// their secrets are already void, so drop any session they might still hold.
    /// </summary>
    private void OnPeersSuperseded(IReadOnlyList<string> deviceIds)
    {
        foreach (var deviceId in deviceIds)
        {
            syncHost?.DisconnectDevice(deviceId);
        }
    }

    /// <summary>
    /// A device just tripped the failed-auth rate limit (worker thread). Record it for the
    /// diagnostics viewer and warn the user; the balloon names the device only, never proof
    /// material or clipboard content.
    /// </summary>
    private void OnDeviceLockedOut(string deviceId)
    {
        LocalDiagnostics.Write("auth_locked_out");
        _ = Dispatcher.InvokeAsync(() =>
        {
            var label = mainViewModel?.Devices
                .FirstOrDefault(device => device.DeviceId == deviceId)?.DisplayName
                ?? Strings.Device_UnknownFallback;
            trayIcon?.ShowAuthThrottleNotice(label);
        });
    }

    /// <summary>
    /// A session stamped or cleared 仅本机保留 marks in the store (worker thread; ADR 0005
    /// §5): refresh the open history list so the annotation appears — or a stale one
    /// drops — without waiting for the next capture or remote commit.
    /// </summary>
    private void OnLocalOnlyMarksChanged()
    {
        _ = Dispatcher.InvokeAsync(async () =>
        {
            try
            {
                if (services?.GetRequiredService<MainViewModel>() is { } viewModel)
                {
                    await viewModel.RefreshFromCaptureAsync();
                }
            }
            catch (Exception exception)
            {
                LocalDiagnostics.Write($"local_only_refresh_failed_{exception.GetType().Name}");
            }
        });
    }

    private void OnRemoteClipsCommitted(IReadOnlyList<RemoteClipApplied> batch)
    {
        _ = Dispatcher.InvokeAsync(async () =>
        {
            if (services is null || batch.Count == 0)
            {
                return;
            }

            try
            {
                var viewModel = services.GetRequiredService<MainViewModel>();
                // The paused / newest-only / text-vs-image policy is the extracted, tested
                // RemoteApplyDecision (the Windows mirror of WindowsAndroidSyncChainTest's
                // scenarios); this handler only executes what it decided. The suppression
                // window keeps our own listener from re-capturing the write.
                var decision = RemoteApplyDecision.Decide(
                    batch,
                    viewModel.IsPaused,
                    viewModel.IsPrivateMode,
                    viewModel.AutoApplyRemote,
                    viewModel.AutoApplyImages);
                if (decision is not RemoteApplyDecision.None)
                {
                    var policy = services.GetRequiredService<ClipboardCapturePolicy>();
                    var adapter = services.GetRequiredService<Win32ClipboardAdapter>();
                    if (decision is RemoteApplyDecision.ApplyImage image)
                    {
                        var store = services.GetRequiredService<SqliteClipboardEventStore>();
                        var bytes = store.Media.ReadAllBytes(image.Clip.ContentHash!);
                        // The pixel digest must ride along: a JPEG apply comes back through
                        // the listener as a DIB→PNG re-encode whose content hash no longer
                        // matches, and without the digest the echo would be captured as a
                        // brand-new local clip and synced straight back to the phone.
                        policy.SuppressNextImage(
                            image.Clip.ContentHash!,
                            DateTimeOffset.UtcNow,
                            ClipboardDataAccessor.TryPixelDigest(bytes));
                        adapter.WriteImage(bytes);
                        LocalDiagnostics.Write("remote_image_applied");
                    }
                    else if (decision is RemoteApplyDecision.ApplyText text)
                    {
                        policy.SuppressNextWrite(text.Clip.Content, DateTimeOffset.UtcNow);
                        // The health endpoint reports real evidence, never "the API exists":
                        // record exactly what this apply attempt did.
                        try
                        {
                            adapter.WriteText(text.Clip.Content);
                        }
                        catch
                        {
                            viewModel.RecordRemoteApplyOutcome(ok: false);
                            throw;
                        }

                        viewModel.RecordRemoteApplyOutcome(ok: true);
                        LocalDiagnostics.Write("remote_applied");
                    }
                }

                // Remote activity proves those devices are alive right now; the device rows
                // and the history list (with origin badges) both need the fresh state.
                await viewModel.NotifyRemoteActivityAsync(
                    batch.Select(applied => applied.OriginDeviceId),
                    DateTimeOffset.UtcNow);
                await viewModel.RefreshFromCaptureAsync();

                // The balloon names the device and count only — clipboard text never
                // appears in notifications (or logs).
                var originId = batch[^1].OriginDeviceId;
                var deviceLabel = viewModel.Devices
                    .FirstOrDefault(device => device.DeviceId == originId)?.DisplayName
                    ?? Strings.Device_UnknownRemote;
                trayIcon?.ShowRemoteClipNotice(deviceLabel, batch.Count);
            }
            catch (Exception exception)
            {
                LocalDiagnostics.Write($"remote_apply_failed_{exception.GetType().Name}");
            }
        });
    }

    protected override void OnExit(ExitEventArgs e)
    {
        CharterThemeManager.Shutdown();
        if (liveRefreshTimer is not null)
        {
            liveRefreshTimer.Stop();
            liveRefreshTimer.Tick -= OnLiveRefreshTick;
            liveRefreshTimer = null;
        }
        if (pairingService is not null)
        {
            pairingService.PairingCompleted -= OnPairingCompleted;
            pairingService.PeersSuperseded -= OnPeersSuperseded;
            pairingService.CancelTicket();
        }
        if (bluetoothHost is not null)
        {
            DetachBluetoothHost(bluetoothHost);
            bluetoothHost.DisposeAsync().AsTask().GetAwaiter().GetResult();
            bluetoothHost = null;
        }
        if (syncHost is not null)
        {
            syncHost.RemoteClipsCommitted -= OnRemoteClipsCommitted;
            syncHost.LocalOnlyMarksChanged -= OnLocalOnlyMarksChanged;
            syncHost.SessionsChanged -= OnPeerSessionsChanged;
            syncHost.DeviceLockedOut -= OnDeviceLockedOut;
            syncHost.PeerStatusChanged -= OnPeerStatusChanged;
            syncHost.DisposeAsync().AsTask().GetAwaiter().GetResult();
        }
        if (clipboardAdapter is not null)
        {
            clipboardAdapter.TextChanged -= OnClipboardTextChanged;
            clipboardAdapter.Faulted -= OnClipboardFaulted;
            clipboardAdapter.Dispose();
        }
        if (mainViewModel is not null)
        {
            mainViewModel.PropertyChanged -= OnViewModelPropertyChanged;
            mainViewModel.Devices.CollectionChanged -= OnDevicesChanged;
        }
        if (hotkeyManager is not null)
        {
            hotkeyManager.Pressed -= OnGlobalHotkeyPressed;
            hotkeyManager.Dispose();
            hotkeyManager = null;
        }
        trayFlyout?.Close();
        trayIcon?.Dispose();
        services?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        base.OnExit(e);
    }

    private async void OnClipboardTextChanged(object? sender, ClipboardTextChangedEventArgs e)
    {
        if (services is null)
        {
            return;
        }

        try
        {
            LocalDiagnostics.Write("text_changed");
            // Reaching this handler means the adapter read the clipboard fine, so any
            // earlier fault is over; the conduit capture segment returns to normal.
            if (mainViewModel is { CaptureFaulted: true })
            {
                mainViewModel.CaptureFaulted = false;
            }

            var captureService = services.GetRequiredService<ClipboardCaptureService>();
            var result = await captureService.CaptureAsync(new ClipboardCandidate(
                e.Text,
                e.SourceProcess,
                e.CapturedAt,
                e.ImageBytes,
                e.ImageMimeType,
                e.PixelDigest));
            if (result is CaptureResult.Stored or CaptureResult.StoredImage)
            {
                LocalDiagnostics.Write(result is CaptureResult.StoredImage ? "capture_image_stored" : "capture_stored");
                // An accepted capture supersedes any lingering 超限 local-only strip.
                mainViewModel?.NoteCaptureStored();
                if (MainWindow?.DataContext is MainViewModel viewModel)
                {
                    await viewModel.RefreshFromCaptureAsync();
                }
            }
            else if (result is CaptureResult.Rejected rejected)
            {
                LocalDiagnostics.Write($"capture_rejected_{rejected.Reason}");
                if (rejected.Reason == CaptureRejectionReason.TooLarge)
                {
                    // 超限内容本机保留 + 明确提示，绝不静默（manual-qa-checklist §3）: banner in
                    // the main window, and a balloon when the window is hidden in the tray.
                    mainViewModel?.NoteCaptureRejected(rejected.Reason);
                    if (MainWindow is not { IsVisible: true })
                    {
                        trayIcon?.ShowOversizeClipNotice();
                    }
                }
            }
        }
        catch (Exception exception)
        {
            LocalDiagnostics.Write($"capture_failed_{exception.GetType().Name}");
            System.Diagnostics.Debug.WriteLine($"Clipboard capture failed with {exception.GetType().Name}.");
        }
    }

    private void OnClipboardFaulted(object? sender, ClipboardAdapterFaultEventArgs e)
    {
        LocalDiagnostics.Write($"adapter_fault_{e.Operation}_{e.Exception.GetType().Name}");
        _ = Dispatcher.InvokeAsync(() =>
        {
            if (mainViewModel is not null)
            {
                mainViewModel.CaptureFaulted = true;
            }
        });
    }
}
