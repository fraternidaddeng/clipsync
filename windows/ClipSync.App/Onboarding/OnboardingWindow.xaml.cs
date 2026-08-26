using System.IO;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using ClipSync.App.Localization;
using ClipSync.App.Pairing;
using ClipSync.App.Sync;
using ClipSync.Core.Onboarding;
using ClipSync.Core.Storage;
using ClipSync.Peer.Pairing;

namespace ClipSync.App.Onboarding;

/// <summary>
/// The first-open tutorial (the Windows five-step mirror of the Android onboarding):
/// welcome → pairing (a live QR, not an illustration) → 特权直读 overview → the optional
/// Bluetooth fallback → send-off. Shown once (<see cref="FirstRunStore"/>), replayable from
/// 偏好 · 帮助 · 重新查看引导. Never a trap: every step but the last carries 稍后设置, and
/// every way out — walked to the end, skipped, or just closed — marks the tutorial seen.
/// </summary>
public partial class OnboardingWindow : Window
{
    /// <summary>The tutorial QR's intended edge in device-independent units (matches the XAML frame).</summary>
    private const double QrEdgeDips = 200;

    private readonly PairingService? pairing;
    private readonly PeerSyncHost? host;
    private readonly Action markSeen;
    private readonly Action openConduit;
    private readonly DispatcherTimer countdown;
    private DateTimeOffset expiresAt;
    private string? currentPayloadJson;
    private int stepIndex;
    private bool ticketActive;
    private bool pairedDuringOnboarding;

    /// <param name="pairing">Null when the peer endpoint failed to start — the pair step then states that fact.</param>
    /// <param name="host">Same lifetime as <paramref name="pairing"/>; carries the addresses, port and fingerprint.</param>
    /// <param name="markSeen">Persists the seen flag; invoked once on close, whichever way the tutorial ends.</param>
    /// <param name="openConduit">The send-off's 前往通路页: focuses the conduit page in the main window.</param>
    public OnboardingWindow(PairingService? pairing, PeerSyncHost? host, Action markSeen, Action openConduit)
    {
        InitializeComponent();
        // 阿拉伯语 RTL（P1#16）：整窗镜像；二维码与指纹是机器文本，XAML 里钉回 LTR。
        FlowDirection = LocalizationManager.WindowFlowDirection;
        this.pairing = pairing;
        this.host = host;
        this.markSeen = markSeen;
        this.openConduit = openConduit;

        countdown = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        countdown.Tick += OnCountdownTick;
        if (pairing is not null)
        {
            pairing.PairingCompleted += OnPairingCompleted;
        }

        Closed += OnClosed;
        ShowStep(0);
    }

    /// <summary>Shows one step; entering/leaving the pair step also arms/retires the live QR ticket.</summary>
    private void ShowStep(int index)
    {
        stepIndex = index;
        var panels = new[] { StepWelcome, StepPair, StepPrivileged, StepBluetooth, StepFinish };
        for (var i = 0; i < panels.Length; i++)
        {
            panels[i].Visibility = i == index ? Visibility.Visible : Visibility.Collapsed;
        }

        // Hidden (not collapsed) keeps the dots centred against the skip link.
        BackButton.Visibility = index > 0 ? Visibility.Visible : Visibility.Hidden;
        var last = OnboardingSteps.IsLast(index);
        SkipButton.Visibility = last ? Visibility.Collapsed : Visibility.Visible;
        NextButton.Visibility = last ? Visibility.Collapsed : Visibility.Visible;
        FinishPanel.Visibility = last ? Visibility.Visible : Visibility.Collapsed;
        UpdateDots();

        if (OnboardingSteps.All[index] == OnboardingStep.Pair)
        {
            EnterPairStep();
        }
        else
        {
            LeavePairStep();
        }
    }

    /// <summary>Progress as quiet dots — the walked path fills flow-blue, the rest stays line-grey.</summary>
    private void UpdateDots()
    {
        DotsPanel.Children.Clear();
        for (var i = 0; i < OnboardingSteps.All.Count; i++)
        {
            var edge = i == stepIndex ? 7.0 : 6.0;
            var dot = new Ellipse
            {
                Width = edge,
                Height = edge,
                Margin = new Thickness(3, 0, 3, 0),
                VerticalAlignment = VerticalAlignment.Center,
            };
            // Resource references (not frozen brushes) so the dots follow day/night live.
            dot.SetResourceReference(Shape.FillProperty, i <= stepIndex ? "CsFlowBrush" : "CsLine2Brush");
            DotsPanel.Children.Add(dot);
        }

        var progress = Strings.Format(
            nameof(Strings.Onboarding_StepProgressFormat), stepIndex + 1, OnboardingSteps.All.Count);
        AutomationProperties.SetName(DotsPanel, progress);
        DotsPanel.ToolTip = progress;
    }

    // ===== 2 · 配对步：真实票据（与配对窗同一 PairingService，同一次性语义） =====

    private void EnterPairStep()
    {
        if (pairedDuringOnboarding)
        {
            // The phone already scanned during this walk; a fresh ticket would be pointless.
            QrPanel.Visibility = Visibility.Collapsed;
            PairNoteBox.Visibility = Visibility.Collapsed;
            PairDoneBox.Visibility = Visibility.Visible;
            return;
        }

        if (pairing is null || host is not { IsRunning: true })
        {
            // Without the peer endpoint a QR could only fail; state the fact instead.
            QrPanel.Visibility = Visibility.Collapsed;
            ShowPairNote(Strings.Pairing_PeerDownBody);
            return;
        }

        DeviceNameText.Text = Environment.MachineName;
        FingerprintText.Text = GroupedFingerprint(host.CertificateFingerprint);
        RefreshTicket();
    }

    /// <summary>Leaving the step (or the window) retires the QR: a hidden code's secret is dead.</summary>
    private void LeavePairStep()
    {
        countdown.Stop();
        CountdownText.Text = string.Empty;
        if (ticketActive)
        {
            pairing?.CancelTicket();
            ticketActive = false;
        }

        currentPayloadJson = null;
        QrImage.Source = null;
    }

    private void RefreshTicket()
    {
        if (pairing is null || host is null)
        {
            return;
        }

        var hosts = host.ReachableHosts;
        if (hosts.Count == 0)
        {
            // Without a reachable address a phone cannot connect; keep the token cancelled
            // rather than rendering a code that can only fail (same rule as the pairing window).
            pairing.CancelTicket();
            ticketActive = false;
            currentPayloadJson = null;
            QrImage.Source = null;
            QrPanel.Visibility = Visibility.Collapsed;
            ShowPairNote(Strings.Pairing_NoLanAddress);
            countdown.Stop();
            CountdownText.Text = string.Empty;
            return;
        }

        PairNoteBox.Visibility = Visibility.Collapsed;
        QrPanel.Visibility = Visibility.Visible;
        var ticket = pairing.IssueTicket();
        ticketActive = true;
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
    /// (the pairing window's recipe, ui-gap-audit P3).
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
    private static string GroupedFingerprint(string fingerprint)
    {
        var groups = fingerprint.ToUpperInvariant().Chunk(4).Select(chunk => new string(chunk));
        return string.Join('\n', groups.Chunk(8).Select(line => string.Join(' ', line)));
    }

    /// <summary>
    /// The phone completed the ritual while the tutorial was up (worker thread): retire the
    /// QR and state the fact in flow-blue — the remaining steps still walk normally.
    /// </summary>
    private void OnPairingCompleted(PairedDevice device) =>
        _ = Dispatcher.InvokeAsync(() =>
        {
            pairedDuringOnboarding = true;
            LeavePairStep();
            if (OnboardingSteps.All[stepIndex] == OnboardingStep.Pair)
            {
                QrPanel.Visibility = Visibility.Collapsed;
                PairNoteBox.Visibility = Visibility.Collapsed;
                PairDoneBox.Visibility = Visibility.Visible;
            }
        });

    private void ShowPairNote(string text)
    {
        PairNoteText.Text = text;
        PairNoteBox.Visibility = Visibility.Visible;
    }

    // ===== chrome =====

    private void OnNextClicked(object sender, RoutedEventArgs e) => ShowStep(OnboardingSteps.Next(stepIndex));

    private void OnBackClicked(object sender, RoutedEventArgs e) => ShowStep(OnboardingSteps.Previous(stepIndex));

    private void OnSkipClicked(object sender, RoutedEventArgs e) => Close();

    private void OnStartUsingClicked(object sender, RoutedEventArgs e) => Close();

    private void OnOpenConduitClicked(object sender, RoutedEventArgs e)
    {
        openConduit();
        Close();
    }

    private void OnCloseClicked(object sender, RoutedEventArgs e) => Close();

    private void OnClosed(object? sender, EventArgs e)
    {
        LeavePairStep();
        countdown.Tick -= OnCountdownTick;
        if (pairing is not null)
        {
            pairing.PairingCompleted -= OnPairingCompleted;
        }

        // Every exit counts as seen — walked, skipped, or closed. The tutorial never nags;
        // 偏好 · 帮助 · 重新查看引导 is the honest way back.
        markSeen();
    }
}
