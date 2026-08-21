using ClipSync.App.Media;
using ClipSync.Core.Media;
using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

public sealed record HistoryItemViewModel(
    Guid EventId,
    string Text,
    string Source,
    string CreatedAt,
    string Kind = "text",
    string? MimeType = null,
    int? EncodedBytes = null,
    int? PixelWidth = null,
    int? PixelHeight = null,
    string? ContentHash = null,
    string? ThumbnailPath = null)
{
    public bool IsImage => string.Equals(Kind, "image", StringComparison.Ordinal);

    public string Preview => IsImage
        ? Strings.FormatImagePreview(MimeType ?? "image", PixelWidth, PixelHeight, EncodedBytes)
        : Text;

    public static HistoryItemViewModel FromEntry(ClipboardHistoryEntry entry, MediaBlobStore? media = null)
    {
        string? thumbnail = null;
        if (entry.IsImage && media is not null && !string.IsNullOrEmpty(entry.ContentHash))
        {
            thumbnail = ImageThumbnail.Ensure(media, entry.ContentHash);
        }

        return new(
            entry.EventId,
            entry.Text,
            entry.SourceProcess ?? Strings.UnknownSource,
            entry.CreatedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture),
            entry.Kind,
            entry.MimeType,
            entry.EncodedBytes,
            entry.PixelWidth,
            entry.PixelHeight,
            entry.ContentHash,
            thumbnail);
    }
}
