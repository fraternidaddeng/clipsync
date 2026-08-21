using System.Windows;
using System.Windows.Input;
using ClipSync.App.Media;
using ClipSync.App.ViewModels;

namespace ClipSync.App;

/// <summary>
/// Read-only full-text or image view for one history item. Title stays
/// neutral so a clipboard body never appears in the taskbar or window chrome.
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
        SourceText.Text = Strings.FormatDetailSource(detail.Source);
        CreatedAtText.Text = Strings.FormatDetailTime(detail.CreatedAt);
        ApplyBody(detail);
        PreviewKeyDown += OnPreviewKeyDown;
    }

    private void ApplyBody(ClipDetailPayload detail)
    {
        if (detail.IsImage && !string.IsNullOrWhiteSpace(detail.ThumbnailPath))
        {
            var image = BitmapFile.TryLoad(detail.ThumbnailPath);
            if (image is not null)
            {
                PreviewImage.Source = image;
                PreviewImage.Visibility = Visibility.Visible;
                BodyText.Visibility = Visibility.Collapsed;
                return;
            }
        }

        BodyText.Text = detail.IsImage
            ? Strings.FormatImagePreview(
                detail.MimeType ?? "image",
                detail.PixelWidth,
                detail.PixelHeight,
                detail.EncodedBytes)
            : detail.Text;
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
