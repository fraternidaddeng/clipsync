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
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        await viewModel.InitializeAsync();
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
