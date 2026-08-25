using ClipSync.App.Clipboard;
using ClipSync.App.Diagnostics;
using ClipSync.App.Pairing;
using ClipSync.App.Security;
using ClipSync.App.Sync;
using ClipSync.App.Theme;
using ClipSync.App.Tray;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
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
        collection.AddSingleton<MainViewModel>();
        collection.AddSingleton<MainWindow>();
        services = collection.BuildServiceProvider();

        var store = services.GetRequiredService<SqliteClipboardEventStore>();
        await store.InitializeAsync();
        var viewModel = services.GetRequiredService<MainViewModel>();
        await viewModel.InitializeAsync();
        var mainWindow = services.GetRequiredService<MainWindow>();
        MainWindow = mainWindow;
        trayFlyout = new TrayFlyoutWindow(viewModel);
        trayIcon = TrayIconController.Create(mainWindow, Shutdown, () => trayFlyout?.ShowFlyout());
        mainViewModel = viewModel;
        viewModel.PropertyChanged += OnViewModelPropertyChanged;
        viewModel.Devices.CollectionChanged += OnDevicesChanged;
        UpdateTrayState();

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
            peerEndpointUnavailable ? "本次会话同步未启动（端点启动失败）"
            : mainViewModel.CaptureFaulted ? "剪贴板捕获降级（上次访问失败）"
            : !hasUsableDevice ? "配对一台设备开始同步"
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
            viewModel.DeviceRevoked += OnDeviceRevoked;
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
                imageSyncEnabled: () => viewModel.ImageSyncEnabled);
            syncHost.RemoteClipsCommitted += OnRemoteClipsCommitted;
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
                mainViewModel.UpdateBluetoothStatus(true, false, null, "蓝牙适配器不可用或已关闭");
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
            : mainViewModel.Devices.FirstOrDefault(device => device.DeviceId == deviceId)?.DisplayName ?? "已配对设备";
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
                "对端服务未启动，本次会话无法配对。",
                "剪剪相传",
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
                await services.GetRequiredService<MainViewModel>().RefreshDevicesCommand.ExecuteAsync(null);
            }
        });
    }

    private void OnDeviceRevoked(string deviceId) => syncHost?.DisconnectDevice(deviceId);

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
                .FirstOrDefault(device => device.DeviceId == deviceId)?.DisplayName ?? "未知设备";
            trayIcon?.ShowAuthThrottleNotice(label);
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
                // Paused sync still receives into history but never auto-applies, matching
                // the Android InboxDelivery.autoApplyAllowed gate.
                if (!viewModel.IsPaused)
                {
                    // Only the newest body of the batch reaches the system clipboard; the
                    // suppression window keeps our own listener from re-capturing it.
                    var latest = batch[^1];
                    var policy = services.GetRequiredService<ClipboardCapturePolicy>();
                    var adapter = services.GetRequiredService<Win32ClipboardAdapter>();
                    if (latest.IsImage)
                    {
                        // Images have their own opt-in gate; text's AutoApplyRemote never
                        // writes pixel bytes to the clipboard on its own.
                        if (viewModel.AutoApplyImages && latest.ContentHash is not null)
                        {
                            var store = services.GetRequiredService<SqliteClipboardEventStore>();
                            var bytes = store.Media.ReadAllBytes(latest.ContentHash);
                            policy.SuppressNextImage(latest.ContentHash, DateTimeOffset.UtcNow);
                            adapter.WriteImage(bytes);
                            LocalDiagnostics.Write("remote_image_applied");
                        }
                    }
                    else if (viewModel.AutoApplyRemote)
                    {
                        policy.SuppressNextWrite(latest.Content, DateTimeOffset.UtcNow);
                        adapter.WriteText(latest.Content);
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
                    .FirstOrDefault(device => device.DeviceId == originId)?.DisplayName ?? "远端设备";
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
            if (result is CaptureResult.Stored or CaptureResult.StoredImage
                && MainWindow?.DataContext is MainViewModel viewModel)
            {
                LocalDiagnostics.Write(result is CaptureResult.StoredImage ? "capture_image_stored" : "capture_stored");
                await viewModel.RefreshFromCaptureAsync();
            }
            else if (result is CaptureResult.Rejected rejected)
            {
                LocalDiagnostics.Write($"capture_rejected_{rejected.Reason}");
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
