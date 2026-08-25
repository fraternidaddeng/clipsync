using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using ClipSync.App.Localization;
using ClipSync.App.Sync;
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
    /// <summary>The QR's intended edge in device-independent units (matches the XAML frame).</summary>
    private const double QrEdgeDips = 280;

    // 配对成功的一次性镜面流光（tokens §9 唯一豁免；technique_lab §05 配方）：带宽 36% 窗宽、
    // 倾斜 18°、左缘从 -40% 平移到 130%，宪章缓动 0.9s（dur 的 Windows 皮），走完停一拍再关窗。
    private const double SheenBandWidthFraction = 0.36;
    private const double SheenTravelStartFraction = -0.4;
    private const double SheenTravelEndFraction = 1.3;
    private const double SheenAngleDegrees = 18;
    private const int SheenTravelMs = 900;
    private const int SheenRestMs = 350;

    private readonly PairingService pairing;
    private readonly PeerSyncHost host;
    private readonly DispatcherTimer countdown;
    private DateTimeOffset expiresAt;
    private string? currentPayloadJson;

    public PairingQrWindow(PairingService pairing, PeerSyncHost host)
    {
        InitializeComponent();
        // 阿拉伯语 RTL（P1#16）：整窗镜像；二维码与指纹是机器文本，XAML 里钉回 LTR。
        FlowDirection = LocalizationManager.WindowFlowDirection;
        this.pairing = pairing;
        this.host = host;

        DeviceNameText.Text = Environment.MachineName;
        FingerprintText.Text = TwoLineFingerprint(host.CertificateFingerprint);

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
            currentPayloadJson = null;
            QrImage.Source = null;
            NoHostsBox.Visibility = Visibility.Visible;
            CountdownText.Text = string.Empty;
            countdown.Stop();
            return;
        }

        NoHostsBox.Visibility = Visibility.Collapsed;
        var ticket = pairing.IssueTicket();
        expiresAt = ticket.ExpiresAt;
        var payload = pairing.BuildQrPayload(ticket, hosts, host.Port, host.CertificateFingerprint);
        currentPayloadJson = PairingJson.Serialize(payload);
        RenderQr();

        UpdateCountdownText();
        countdown.Start();
    }

    /// <summary>
    /// Rasters the current payload with whole physical pixels per module and lays the image
    /// out at the bitmap's exact physical size, so no DPI scale resamples the modules
    /// (ui-gap-audit P3: the fixed 8px module blurred at 125%/150%).
    /// </summary>
    private void RenderQr()
    {
        if (currentPayloadJson is null)
        {
            return;
        }

        var pixelsPerDip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var rendered = PairingQrRenderer.RenderPngForDpi(currentPayloadJson, pixelsPerDip, QrEdgeDips);

        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = new MemoryStream(rendered.Png);
        image.EndInit();
        image.Freeze();
        QrImage.Source = image;
        QrImage.Width = rendered.PixelEdge / pixelsPerDip;
        QrImage.Height = rendered.PixelEdge / pixelsPerDip;
    }

    /// <summary>Moving to a monitor with another scale re-rasters the same ticket — never a new one.</summary>
    protected override void OnDpiChanged(DpiScale oldDpi, DpiScale newDpi)
    {
        base.OnDpiChanged(oldDpi, newDpi);
        RenderQr();
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

        CountdownText.Text = Strings.Format(nameof(Strings.Pairing_CountdownFormat), remaining);
    }

    /// <summary>Four-character groups, eight per line: humans compare groups, not character streams.</summary>
    private static string TwoLineFingerprint(string fingerprint)
    {
        var groups = fingerprint.Chunk(4).Select(chunk => new string(chunk));
        return string.Join('\n', groups.Chunk(8).Select(line => string.Join(' ', line)));
    }

    private void OnPairingCompleted(PairedDevice device) =>
        _ = Dispatcher.InvokeAsync(CelebrateThenClose);

    /// <summary>
    /// The ritual's closing beat: one mirror sheen sweeps the window, then it closes.
    /// With animations off (减弱动效) the window closes immediately, exactly as before —
    /// the system's choice is a fact.
    /// </summary>
    private void CelebrateThenClose()
    {
        // The pairing is done: no fresh ticket may replace the success beat.
        countdown.Stop();
        if (!SystemParameters.ClientAreaAnimation)
        {
            Close();
            return;
        }

        var width = ActualWidth;
        var height = ActualHeight;
        SheenBand.Width = width * SheenBandWidthFraction;
        // Triple-height overdraw so the 18° lean still covers the window; the host clips.
        SheenBand.Height = height * 3;
        Canvas.SetLeft(SheenBand, 0);
        Canvas.SetTop(SheenBand, -height);
        var translate = new TranslateTransform(width * SheenTravelStartFraction, 0);
        SheenBand.RenderTransform = new TransformGroup
        {
            Children =
            {
                new RotateTransform(SheenAngleDegrees, SheenBand.Width / 2, height * 1.5),
                translate
            }
        };
        SheenHost.Visibility = Visibility.Visible;

        var sweep = new DoubleAnimationUsingKeyFrames();
        sweep.KeyFrames.Add(new SplineDoubleKeyFrame(
            width * SheenTravelEndFraction,
            KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(SheenTravelMs)),
            new KeySpline(0.16, 1, 0.3, 1)));
        sweep.Completed += OnSheenCompleted;
        translate.BeginAnimation(TranslateTransform.XProperty, sweep);
    }

    /// <summary>The sweep is done; hold one quiet beat (停顿比划过更重要), then close.</summary>
    private void OnSheenCompleted(object? sender, EventArgs e)
    {
        var rest = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(SheenRestMs) };
        rest.Tick += (_, _) =>
        {
            rest.Stop();
            Close();
        };
        rest.Start();
    }

    private void OnRegenerateClicked(object sender, RoutedEventArgs e) => RefreshTicket();

    private void OnCloseClicked(object sender, RoutedEventArgs e) => Close();

    private void OnClosed(object? sender, EventArgs e)
    {
        countdown.Stop();
        countdown.Tick -= OnCountdownTick;
        pairing.PairingCompleted -= OnPairingCompleted;
        pairing.CancelTicket();
    }
}
