using System.Windows;
using ClipSync.App.ViewModels;

namespace ClipSync.App;

public partial class MainWindow : Window
{
    private readonly MainViewModel viewModel;

    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        this.viewModel = viewModel;
        DataContext = viewModel;
        Loaded += OnLoaded;
        Closing += OnClosing;
        viewModel.DetailRequested += OpenSelectedDetail;
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
