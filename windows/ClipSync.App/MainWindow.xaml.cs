using System.Windows;
using ClipSync.App.ViewModels;

namespace ClipSync.App;

public partial class MainWindow : Window
{
    private readonly MainViewModel viewModel;

    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        // 阿拉伯语 RTL（P1#16）：整窗镜像（导航、通路四段、偏好行随文化方向翻转）；
        // 指纹、快捷键、监听地址等机器文本在 XAML 里各自钉回 LTR。
        FlowDirection = Localization.LocalizationManager.WindowFlowDirection;
        this.viewModel = viewModel;
        DataContext = viewModel;
        Loaded += OnLoaded;
        Closing += OnClosing;
        viewModel.DetailRequested += OpenSelectedDetail;
        // 无线配对二维码：payload 是数据（VM），像素是视图的事——文本一变就按当前 DPI 重栅格。
        viewModel.PropertyChanged += OnViewModelPropertyChanged;
    }

    /// <summary>The wireless pairing QR's intended edge in device-independent units (matches the XAML frame).</summary>
    private const double WirelessQrEdgeDips = 200;

    private void OnViewModelPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainViewModel.WirelessQrText))
        {
            RenderWirelessQr();
        }
    }

    /// <summary>
    /// Rasters the wireless-pairing QR with whole physical pixels per module and lays the
    /// image out at the bitmap's exact physical size, so no DPI scale resamples the modules
    /// (same recipe as PairingQrWindow / ui-gap-audit P3).
    /// </summary>
    private void RenderWirelessQr()
    {
        var payload = viewModel.WirelessQrText;
        if (payload.Length == 0)
        {
            WirelessQrImage.Source = null;
            return;
        }

        var pixelsPerDip = System.Windows.Media.VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var rendered = Pairing.PairingQrRenderer.RenderPngForDpi(payload, pixelsPerDip, WirelessQrEdgeDips);
        var image = new System.Windows.Media.Imaging.BitmapImage();
        image.BeginInit();
        image.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
        image.StreamSource = new System.IO.MemoryStream(rendered.Png);
        image.EndInit();
        image.Freeze();
        WirelessQrImage.Source = image;
        WirelessQrImage.Width = rendered.PixelEdge / pixelsPerDip;
        WirelessQrImage.Height = rendered.PixelEdge / pixelsPerDip;
    }

    /// <summary>Moving to a monitor with another scale re-rasters the same payload — never a new secret.</summary>
    protected override void OnDpiChanged(DpiScale oldDpi, DpiScale newDpi)
    {
        base.OnDpiChanged(oldDpi, newDpi);
        RenderWirelessQr();
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        await viewModel.InitializeAsync();

        // 首启落「通路」页（pc-ui-inventory #14）：还没配对也没有任何历史时，
        // 网络段的「配对新设备」应当是第一眼；其余情况保持历史页。
        if (!viewModel.HasPairedDevices && viewModel.History.Count == 0)
        {
            NavConduit.IsChecked = true;
        }
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (Application.Current.ShutdownMode == ShutdownMode.OnExplicitShutdown)
        {
            e.Cancel = true;
            Hide();
        }
    }

    // 改动即生效：开关在点击时保存，文本框在失焦时保存（没有「保存设置」按钮）。
    private async void OnSettingToggled(object sender, RoutedEventArgs e) =>
        await viewModel.SaveSettingsFromUiAsync();

    // 语言（P1#16）：下拉改选即落库；界面语言重启后生效（行内赭注如实陈述）。
    // IsLoaded 闸门滤掉窗口构造期间绑定初始化触发的 SelectionChanged。
    private async void OnLanguageSelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (IsLoaded)
        {
            await viewModel.SaveSettingsFromUiAsync();
        }
    }

    private async void OnSettingLostFocus(object sender, RoutedEventArgs e) =>
        await viewModel.SaveSettingsFromUiAsync();

    private async void OnRetentionMinusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionDays = System.Math.Max(1, viewModel.RetentionDays - 1);
        await viewModel.SaveSettingsFromUiAsync();
    }

    private async void OnRetentionPlusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionDays = System.Math.Min(3650, viewModel.RetentionDays + 1);
        await viewModel.SaveSettingsFromUiAsync();
    }

    // 保留条数（P1-15）：100–2000，每步 100——逐条步进对 2000 的量级没有意义。
    private async void OnMaxEntriesMinusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionMaxEntries = System.Math.Max(100, viewModel.RetentionMaxEntries - 100);
        await viewModel.SaveSettingsFromUiAsync();
    }

    private async void OnMaxEntriesPlusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionMaxEntries = System.Math.Min(2000, viewModel.RetentionMaxEntries + 100);
        await viewModel.SaveSettingsFromUiAsync();
    }

    // 呼出浮窗快捷键（P1-9）：输入框内直接按组合键设置，Backspace/Delete/Esc 清除。
    // Alt 组合以 Key.System 到达，真实按键在 SystemKey 里；不成组合的按键（如 Tab 导航）
    // 不拦截，键盘用户仍能离开输入框。
    private async void OnHotkeyBoxPreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        var key = e.Key == System.Windows.Input.Key.System ? e.SystemKey : e.Key;
        if (key is System.Windows.Input.Key.Back
            or System.Windows.Input.Key.Delete
            or System.Windows.Input.Key.Escape)
        {
            e.Handled = true;
            if (viewModel.FlyoutHotkey.Length > 0)
            {
                viewModel.FlyoutHotkey = string.Empty;
                await viewModel.SaveSettingsFromUiAsync();
            }

            return;
        }

        var gesture = Tray.HotkeyGesture.FromKey(System.Windows.Input.Keyboard.Modifiers, key);
        if (gesture is null)
        {
            return;
        }

        e.Handled = true;
        if (gesture == viewModel.FlyoutHotkey)
        {
            return;
        }

        viewModel.FlyoutHotkey = gesture;
        await viewModel.SaveSettingsFromUiAsync();
    }

    private void OnPairNewDeviceClicked(object sender, RoutedEventArgs e) =>
        ((App)Application.Current).ShowPairingWindow(this);

    private void OnHistoryItemDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e) =>
        OpenSelectedDetail();

    /// <summary>
    /// Opens the read-only detail window for the selected clip. Copy inside the
    /// detail view routes through the same suppression path as the list buttons.
    /// </summary>
    private void OpenSelectedDetail()
    {
        var detail = viewModel.GetSelectedDetail();
        if (detail is null)
        {
            return;
        }

        var window = new DetailWindow(detail, () =>
        {
            if (detail.IsImage)
            {
                if (detail.ContentHash is not null)
                {
                    viewModel.CopyImage(detail.ContentHash);
                }
            }
            else
            {
                viewModel.CopyText(detail.Text);
            }
        })
        {
            Owner = this
        };
        window.ShowDialog();
    }

    // 空状态的幽灵「去配对」：与 Android 同一动线——先到通路页的网络段，
    // 让用户看见配对在整条通路里的位置，而不是直接弹二维码。
    private void OnGoToConduitClicked(object sender, RoutedEventArgs e) =>
        NavConduit.IsChecked = true;

    // 自绘 chrome 的窗控三钮（WindowChrome 去掉了系统标题栏）。
    private void OnMinimizeClicked(object sender, RoutedEventArgs e) =>
        WindowState = WindowState.Minimized;

    private void OnMaxRestoreClicked(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized
            ? WindowState.Normal
            : WindowState.Maximized;

    // 与系统关闭按钮同路径：OnClosing 把它变成「隐藏到托盘」。
    private void OnCloseClicked(object sender, RoutedEventArgs e) => Close();
}
