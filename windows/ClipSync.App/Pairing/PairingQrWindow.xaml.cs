using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using ClipSync.App.Sync;
using ClipSync.App;
using ClipSync.Core.Storage;
using ClipSync.Peer.Pairing;

namespace ClipSync.App.Pairing;

/// <summary>
/// Shows the one-time pairing QR code. A fresh token is issued when the window opens,
/// when the countdown expires, and on demand; closing the window invalidates the token.
/// A completed pairing closes the window automatically.
/// </summary>
public partial class PairingQrWindow : Window
{
    private readonly PairingService pairing;
    private readonly PeerSyncHost host;
    private readonly DispatcherTimer countdown;
    private DateTimeOffset expiresAt;

    public PairingQrWindow(PairingService pairing, PeerSyncHost host)
    {
        InitializeComponent();
        this.pairing = pairing;
        this.host = host;

        DeviceNameText.Text = Strings.FormatComputerName(Environment.MachineName);
        FingerprintText.Text = Strings.FormatCertificate(PairingQrRenderer.FormatFingerprint(host.CertificateFingerprint));

        countdown = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        countdown.Tick += OnCountdownTick;

        pairing.PairingCompleted += OnPairingCompleted;
        Closed += OnClosed;

        RefreshTicket();
    }

    private void RefreshTicket()
    {
        var hosts = host.ReachableHosts;
        if (hosts.Count == 0)
        {
            // Without a reachable address a phone cannot connect; keep the token cancelled
            // rather than rendering a code that can only fail.
            pairing.CancelTicket();
            QrImage.Source = null;
            NoHostsText.Visibility = Visibility.Visible;
            CountdownText.Text = string.Empty;
            countdown.Stop();
            return;
        }

        NoHostsText.Visibility = Visibility.Collapsed;
        var ticket = pairing.IssueTicket();
        expiresAt = ticket.ExpiresAt;
        var payload = pairing.BuildQrPayload(ticket, hosts, host.Port, host.CertificateFingerprint);
        var json = PairingJson.Serialize(payload);
        WritePayloadDump(json);
        var png = PairingQrRenderer.RenderPng(json);

        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = new MemoryStream(png);
        image.EndInit();
        image.Freeze();
        QrImage.Source = image;

        UpdateCountdownText();
        countdown.Start();
    }

    private void OnCountdownTick(object? sender, EventArgs e)
    {
        if (DateTimeOffset.UtcNow >= expiresAt)
        {
            // An expired token is useless; issue a fresh one so the user never scans a dead code.
            RefreshTicket();
            return;
        }

        UpdateCountdownText();
    }

    private void UpdateCountdownText()
    {
        var remaining = expiresAt - DateTimeOffset.UtcNow;
        if (remaining < TimeSpan.Zero)
        {
            remaining = TimeSpan.Zero;
        }

        CountdownText.Text = Strings.FormatPairingCountdown(remaining);
    }

    private void OnPairingCompleted(PairedDevice device) =>
        _ = Dispatcher.InvokeAsync(Close);

    private void OnRegenerateClicked(object sender, RoutedEventArgs e) => RefreshTicket();

    private void OnCloseClicked(object sender, RoutedEventArgs e) => Close();

    private static void WritePayloadDump(string json)
    {
        var path = Environment.GetEnvironmentVariable("CLIPSYNC_PAIRING_PAYLOAD_PATH");
        if (string.IsNullOrWhiteSpace(path))
        {
            path = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ClipSync",
                "last-pairing-qr.json");
        }

        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        File.WriteAllText(path, json);
    }

    private void OnClosed(object? sender, EventArgs e)
    {
        countdown.Stop();
        countdown.Tick -= OnCountdownTick;
        pairing.PairingCompleted -= OnPairingCompleted;
        pairing.CancelTicket();
    }
}
