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
        SourceText.Text = $"来源：{detail.Source}";
        CreatedAtText.Text = $"时间：{detail.CreatedAt}";
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
                PreviewBorder.Visibility = Visibility.Visible;
                BodyText.Visibility = Visibility.Collapsed;
                return;
            }
        }

        BodyText.Text = detail.IsImage
            ? FormatImagePreview(detail)
            : detail.Text;
    }

    private static string FormatImagePreview(ClipDetailPayload detail)
    {
        if (detail.PixelWidth is null || detail.PixelHeight is null)
        {
            return string.IsNullOrEmpty(detail.MimeType) ? "图片" : detail.MimeType;
        }

        var size = detail.EncodedBytes is null
            ? "?"
            : detail.EncodedBytes.Value < 1024
                ? $"{detail.EncodedBytes.Value} B"
                : $"{detail.EncodedBytes.Value / 1024.0:0.#} KiB";
        return $"{detail.MimeType ?? "图片"} {detail.PixelWidth}×{detail.PixelHeight} · {size}";
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
