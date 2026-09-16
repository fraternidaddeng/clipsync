using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using ClipSync.App.Localization;
using ClipSync.Peer.Pairing;

namespace ClipSync.App.Pairing;

/// <summary>
/// The explicit user confirmation required by protocol section 9. Closing the window
/// without clicking Approve counts as a rejection.
/// </summary>
public partial class PairingApprovalWindow : Window
{
    private const double SheenBandWidthFraction = 0.36;
    private const double SheenTravelStartFraction = -0.4;
    private const double SheenTravelEndFraction = 1.3;
    private const double SheenAngleDegrees = 18;
    private const int SheenTravelMs = 1300;
    private const int SheenRestMs = 260;

    public PairingApprovalWindow(PairingCandidate candidate)
    {
        InitializeComponent();
        // 阿拉伯语 RTL（P1#16）：批准仪式整窗镜像。
        FlowDirection = LocalizationManager.WindowFlowDirection;
        DeviceNameText.Text = candidate.DisplayName;
        PlatformText.Text = candidate.Platform switch
        {
            "android" => Strings.Approval_PlatformAndroid,
            "windows" => Strings.Approval_PlatformWindows,
            _ => candidate.Platform
        };
        RepairWarningBox.Visibility = candidate.IsRepair ? Visibility.Visible : Visibility.Collapsed;
        SupersedeNoticeBox.Visibility = candidate.ReplacesSameNamePeer ? Visibility.Visible : Visibility.Collapsed;
    }

    public bool Approved { get; private set; }

    private void OnApproveClicked(object sender, RoutedEventArgs e)
    {
        Approved = true;
        if (!SystemParameters.ClientAreaAnimation)
        {
            Close();
            return;
        }

        CelebrateThenClose();
    }

    /// <summary>
    /// The ritual's closing beat: one mirror sheen sweeps the window, then it closes.
    /// With animations off (减弱动效) the window closes immediately.
    /// </summary>
    private void CelebrateThenClose()
    {
        IsEnabled = false;

        var width = ActualWidth;
        var height = ActualHeight;
        SheenBand.Width = width * SheenBandWidthFraction;
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

    private void OnRejectClicked(object sender, RoutedEventArgs e) => Close();
}
