using System.Windows;
using System.Windows.Input;
using ClipSync.App.Localization;
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
        // 阿拉伯语 RTL（P1#16）：整窗镜像，正文方向交给文字本身。
        FlowDirection = LocalizationManager.WindowFlowDirection;
        this.copy = copy;
        SourceText.Text = Strings.Format(nameof(Strings.Detail_SourceFormat), detail.Source);
        CreatedAtText.Text = Strings.Format(nameof(Strings.Detail_TimeFormat), detail.CreatedAt);
        ApplyImageMeta(detail);
        ApplyBody(detail);
        PreviewKeyDown += OnPreviewKeyDown;
    }

    /// <summary>
    /// The image itself is the content hero; encoding / dimensions / byte size are the
    /// machine voice and live in a quiet mono annotation line under the header —
    /// they never stand in as the body (user verdict 2026-08-26).
    /// </summary>
    private void ApplyImageMeta(ClipDetailPayload detail)
    {
        if (!detail.IsImage)
        {
            return;
        }

        var summary = ClipSync.App.Ui.ImageMetadata.Summary(
            detail.MimeType, detail.PixelWidth, detail.PixelHeight, detail.EncodedBytes);
        if (summary.Length > 0)
        {
            ImageMetaText.Text = summary;
            ImageMetaText.Visibility = Visibility.Visible;
        }
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

        // Undecodable image: state the fact — the metadata already sits in the
        // annotation line above, so the body never repeats it as pseudo-content.
        BodyText.Text = detail.IsImage
            ? Strings.History_NoPreview
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
