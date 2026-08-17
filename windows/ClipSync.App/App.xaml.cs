using ClipSync.App.Clipboard;
using ClipSync.App.Diagnostics;
using ClipSync.App.Pairing;
using ClipSync.App.Security;
using ClipSync.App.Sync;
using ClipSync.App.Tray;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Peer.Pairing;
using ClipSync.Peer.Sessions;
using Hardcodet.Wpf.TaskbarNotification;
using Microsoft.Extensions.DependencyInjection;
using System.IO;
using System.Windows;

namespace ClipSync.App;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "WPF Application lifetime: every owned resource is disposed in OnExit.")]
public partial class App : Application
{
    private ServiceProvider? services;
    private TaskbarIcon? trayIcon;
    private Win32ClipboardAdapter? clipboardAdapter;
    private PeerSyncHost? syncHost;
    private PairingService? pairingService;
    private PairingQrWindow? pairingWindow;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

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
        trayIcon = TrayIconFactory.Create(mainWindow, Shutdown);

        clipboardAdapter = services.GetRequiredService<Win32ClipboardAdapter>();
        clipboardAdapter.TextChanged += OnClipboardTextChanged;
        clipboardAdapter.Faulted += OnClipboardFaulted;
        clipboardAdapter.Start();
        LocalDiagnostics.Write("listener_started");

        await StartPeerEndpointAsync(dataDirectory, deviceId, store, viewModel);
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
            syncHost = new PeerSyncHost(store, protector, certificate, pairingService);
            syncHost.RemoteClipsCommitted += OnRemoteClipsCommitted;
            await syncHost.StartAsync(viewModel.ExtraBindAddresses);
            viewModel.SyncStatus =
                $"Peer endpoint on port {syncHost.Port}\nDevice {deviceId}\nCert {syncHost.CertificateFingerprint[..16]}…";
            if (string.Equals(
                    Environment.GetEnvironmentVariable("CLIPSYNC_SHOW_PAIRING"),
                    "1",
                    StringComparison.Ordinal))
            {
                _ = Dispatcher.InvokeAsync(() =>
                {
                    if (MainWindow is Window owner)
                    {
                        owner.Show();
                        owner.Activate();
                        ShowPairingWindow(owner);
                    }
                });
            }
        }
        catch (Exception exception)
        {
            LocalDiagnostics.Write($"peer_start_failed_{exception.GetType().Name}");
            viewModel.SyncStatus = "Peer endpoint failed to start; sync is off this session.";
        }
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
                "The peer endpoint is not running, so pairing is unavailable this session.",
                "ClipSync",
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
                if (viewModel.AutoApplyRemote)
                {
                    // Only the newest body of the batch reaches the system clipboard; the
                    // suppression window keeps our own listener from re-capturing it.
                    var latest = batch[^1];
                    services.GetRequiredService<ClipboardCapturePolicy>()
                        .SuppressNextWrite(latest.Content, DateTimeOffset.UtcNow);
                    services.GetRequiredService<Win32ClipboardAdapter>().WriteText(latest.Content);
                    LocalDiagnostics.Write("remote_applied");
                }

                await viewModel.RefreshFromCaptureAsync();
            }
            catch (Exception exception)
            {
                LocalDiagnostics.Write($"remote_apply_failed_{exception.GetType().Name}");
            }
        });
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (pairingService is not null)
        {
            pairingService.PairingCompleted -= OnPairingCompleted;
            pairingService.CancelTicket();
        }
        if (syncHost is not null)
        {
            syncHost.RemoteClipsCommitted -= OnRemoteClipsCommitted;
            syncHost.DisposeAsync().AsTask().GetAwaiter().GetResult();
        }
        if (clipboardAdapter is not null)
        {
            clipboardAdapter.TextChanged -= OnClipboardTextChanged;
            clipboardAdapter.Faulted -= OnClipboardFaulted;
            clipboardAdapter.Dispose();
        }
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
            var captureService = services.GetRequiredService<ClipboardCaptureService>();
            var result = await captureService.CaptureAsync(new ClipboardCandidate(e.Text, e.SourceProcess, e.CapturedAt));
            if (result is CaptureResult.Stored && MainWindow?.DataContext is MainViewModel viewModel)
            {
                LocalDiagnostics.Write("capture_stored");
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

    private static void OnClipboardFaulted(object? sender, ClipboardAdapterFaultEventArgs e) =>
        LocalDiagnostics.Write($"adapter_fault_{e.Operation}_{e.Exception.GetType().Name}");
}
