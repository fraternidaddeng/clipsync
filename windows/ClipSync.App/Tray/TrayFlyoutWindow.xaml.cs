using ClipSync.App.ViewModels;
using System.Windows;
using System.Windows.Threading;

namespace ClipSync.App.Tray;

/// <summary>
/// The tray flyout (tokens §12.6): a 440px quick-glance surface near the notification
/// area with the newest clips, the four-segment rail and the pause toggle. It lives
/// about three seconds unless the pointer is inside, and hides — never closes — so a
/// tray click can bring it right back.
/// </summary>
public partial class TrayFlyoutWindow : Window
{
    /// <summary>How long the flyout stays once the pointer is (or leaves it) outside.</summary>
    private static readonly TimeSpan AutoHideDelay = TimeSpan.FromSeconds(3);

    /// <summary>Margin reserved around the card for the sh-2 drop shadow.</summary>
    private const double ShadowMargin = 12;

    /// <summary>Visual gap between the card edge and the work-area corner.</summary>
    private const double WorkAreaGap = 8;

    private readonly MainViewModel viewModel;
    private readonly DispatcherTimer autoHideTimer;

    public TrayFlyoutWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        // 阿拉伯语 RTL（P1#16）：浮窗整体镜像。
        FlowDirection = Localization.LocalizationManager.WindowFlowDirection;
        this.viewModel = viewModel;
        DataContext = viewModel;
        autoHideTimer = new DispatcherTimer { Interval = AutoHideDelay };
        autoHideTimer.Tick += (_, _) => HideFlyout();
        Deactivated += (_, _) => HideFlyout();
        MouseEnter += (_, _) => autoHideTimer.Stop();
        MouseLeave += (_, _) => RestartAutoHide();
    }

    /// <summary>Shows the flyout anchored to the bottom-right work-area corner (near the tray).</summary>
    public void ShowFlyout()
    {
        Show();
        // SizeToContent resolves during layout; measure first so Top is right on first show.
        UpdateLayout();
        var area = SystemParameters.WorkArea;
        Left = area.Right - ActualWidth + ShadowMargin - WorkAreaGap;
        Top = area.Bottom - ActualHeight + ShadowMargin - WorkAreaGap;
        Activate();
        RestartAutoHide();
    }

    public void HideFlyout()
    {
        autoHideTimer.Stop();
        Hide();
    }

    private void RestartAutoHide()
    {
        autoHideTimer.Stop();
        autoHideTimer.Start();
    }

    private void OnClipCardClicked(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: HistoryItemViewModel item })
        {
            viewModel.CopyItemCommand.Execute(item);
            HideFlyout();
        }
    }

    private async void OnPauseClicked(object sender, RoutedEventArgs e)
    {
        viewModel.IsPaused = !viewModel.IsPaused;
        await viewModel.SaveSettingsFromUiAsync();
        RestartAutoHide();
    }
}
