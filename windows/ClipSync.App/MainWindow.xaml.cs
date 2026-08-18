using System.Windows;
using System.Windows.Input;
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
        viewModel.DetailRequested += OpenSelectedDetail;
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

    private async void OnCaptureSettingToggled(object sender, RoutedEventArgs e) =>
        await viewModel.SaveSettingsFromUiAsync();

    private void OnPairNewDeviceClicked(object sender, RoutedEventArgs e) =>
        ((App)Application.Current).ShowPairingWindow(this);

    private void OnHistoryItemDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (viewModel.ViewSelectedCommand.CanExecute(null))
        {
            viewModel.ViewSelectedCommand.Execute(null);
        }
    }

    private void OpenSelectedDetail()
    {
        var detail = viewModel.GetSelectedDetail();
        if (detail is null)
        {
            return;
        }

        var window = new DetailWindow(detail, () => viewModel.CopyText(detail.Text))
        {
            Owner = this
        };
        window.Show();
    }
}
