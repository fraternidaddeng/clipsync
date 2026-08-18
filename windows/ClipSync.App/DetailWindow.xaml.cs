using System.Windows;
using System.Windows.Input;
using ClipSync.App.ViewModels;

namespace ClipSync.App;

/// <summary>
/// Read-only full-text view for one history item. Title stays neutral so a
/// clipboard body never appears in the taskbar or window chrome.
/// </summary>
public partial class DetailWindow : Window
{
    private readonly Action copy;

    public DetailWindow(ClipDetailPayload detail, Action copy)
    {
        ArgumentNullException.ThrowIfNull(detail);
        ArgumentNullException.ThrowIfNull(copy);

        InitializeComponent();
        this.copy = copy;
        SourceText.Text = detail.Source;
        CreatedAtText.Text = detail.CreatedAt;
        BodyText.Text = detail.Text;
        PreviewKeyDown += OnPreviewKeyDown;
    }

    private void OnCopyClicked(object sender, RoutedEventArgs e) => copy();

    private void OnCloseClicked(object sender, RoutedEventArgs e) => Close();

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            e.Handled = true;
            Close();
        }
    }
}
