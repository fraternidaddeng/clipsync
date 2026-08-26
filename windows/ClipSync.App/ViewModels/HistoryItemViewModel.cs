using ClipSync.App.Localization;
using ClipSync.App.Ui;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;
using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

public sealed record HistoryItemViewModel(
    Guid EventId,
    string Text,
    string Source,
    string CreatedAt,
    bool IsRemote,
    string OriginLabel,
    int OriginAccentIndex,
    ClipContentFormat Format,
    string Kind = "text",
    string? MimeType = null,
    int? EncodedBytes = null,
    int? PixelWidth = null,
    int? PixelHeight = null,
    string? ContentHash = null,
    string? ThumbnailPath = null,
    System.Windows.Media.ImageSource? ThumbnailImage = null,
    bool IsSourceKnown = true,
    bool IsLocalOnly = false)
{
    /// <summary>Shown for remote clips whose origin device is no longer in the paired list.</summary>
    private static string UnknownRemoteLabel => Strings.Device_UnknownRemote;

    /// <summary>Shown in a quiet grey annotation box when the source app could not be resolved.</summary>
    internal static string UnknownSourceLabel => Strings.History_UnknownSource;

    public bool IsImage => string.Equals(Kind, "image", StringComparison.Ordinal);

    /// <summary>
    /// True only when the 56 px preview actually decoded. The list template keys
    /// its honest placeholder (无预览) off this — a load failure must never leave
    /// an unexplained empty box.
    /// </summary>
    public bool HasThumbnail => ThumbnailImage is not null;

    /// <summary>Image rows whose pixels could not be produced at all show the 无预览 badge.</summary>
    public bool ShowsNoPreview => IsImage && !HasThumbnail;

    /// <summary>
    /// Metadata line under the pills: "source · time" for a resolved source app; the
    /// unresolved case moves 未知来源 into its grey annotation box, so only the time stays.
    /// </summary>
    public string MetaLine => IsSourceKnown ? $"{Source} · {CreatedAt}" : CreatedAt;

    /// <summary>
    /// Card body line: the clip text. Image rows have no prose — the thumbnail is
    /// the content hero (user verdict 2026-08-26), so surfaces without pixels (the
    /// tray flyout without a thumbnail) fall back to the human word 图片, never to
    /// a technical metadata headline.
    /// </summary>
    public string Preview => IsImage ? Strings.Format_Image : Text;

    /// <summary>Encoding pill ("PNG"); empty when the mime type is unknown.</summary>
    public string ImageFormatLabel => ImageMetadata.FormatLabel(MimeType);

    /// <summary>Dimensions pill ("320×200"); empty when either dimension is unknown.</summary>
    public string ImageDimensionsLabel => ImageMetadata.Dimensions(PixelWidth, PixelHeight) ?? string.Empty;

    /// <summary>Byte-size pill ("96 B" / "2 KiB"); empty when the count is unknown.</summary>
    public string ImageByteSizeLabel => ImageMetadata.ByteSize(EncodedBytes) ?? string.Empty;

    public bool HasImageFormatLabel => IsImage && ImageFormatLabel.Length > 0;

    public bool HasImageDimensionsLabel => IsImage && ImageDimensionsLabel.Length > 0;

    public bool HasImageByteSizeLabel => IsImage && ImageByteSizeLabel.Length > 0;

    /// <summary>Badge text per format (ADR 0003 词汇); plain text carries no badge.</summary>
    public string FormatLabel => IsImage ? Strings.Format_Image : Format switch
    {
        ClipContentFormat.Link => Strings.Filter_Link,
        ClipContentFormat.Email => Strings.Filter_Email,
        ClipContentFormat.Otp => Strings.Filter_Otp,
        ClipContentFormat.Credential => Strings.Filter_Credential,
        _ => string.Empty,
    };

    /// <summary>
    /// Fixed neighbour-hue slot per format (ADR 0003): the badge borrows the
    /// annotation chroma tier (tokens §4), never the state colours — a
    /// credential is a fact to find again, not an alarm, so no red anywhere.
    /// </summary>
    public int FormatAccentIndex => IsImage ? 5 : Format switch
    {
        ClipContentFormat.Email => 1,      // 青灰
        ClipContentFormat.Link => 2,       // 水蓝
        ClipContentFormat.Otp => 3,        // 蓝紫
        ClipContentFormat.Credential => 4, // 藕紫
        _ => DeviceAccent.None,
    };

    /// <summary>A quiet card is the default: only non-plain formats (and images) show a badge.</summary>
    public bool HasFormatBadge => IsImage || Format != ClipContentFormat.Plain;

    /// <summary>Accessible name for the flyout copy card, resolved in the current UI language.</summary>
    public string CopyAccessibleName => Strings.Format(nameof(Strings.Flyout_CopyFromFormat), OriginLabel);

    /// <summary>
    /// 仅本机保留 (ADR 0005 §5): this local image was terminated as a `local_only`
    /// marker on a text-only path (Bluetooth fallback, or the image gate off), so
    /// the peer's cursor moved past it and it will never sync — IP recovery does
    /// not retransmit. A factual grey annotation, not an alarm.
    /// </summary>
    public bool ShowsLocalOnlyBadge => IsImage && IsLocalOnly;

    public static HistoryItemViewModel FromEntry(
        ClipboardHistoryEntry entry,
        string localDeviceId,
        Func<string, PairedDeviceViewModel?>? deviceLookup = null,
        MediaBlobStore? media = null)
    {
        var isRemote = !string.Equals(entry.OriginDeviceId, localDeviceId, StringComparison.Ordinal);
        var device = isRemote ? deviceLookup?.Invoke(entry.OriginDeviceId) : null;
        string? thumbnail = null;
        System.Windows.Media.ImageSource? thumbnailImage = null;
        if (entry.IsImage && media is not null && !string.IsNullOrEmpty(entry.ContentHash))
        {
            // Decode once per refresh and freeze: the frozen bitmap is what the list
            // binds, so container recycling never re-runs a converter and a transient
            // file error can't blank an already-loaded row. LoadForList self-heals a
            // corrupt cached thumbnail and falls back to the blob, so the 无预览
            // placeholder appears only when no pixels can be produced at all.
            (thumbnail, thumbnailImage) =
                ClipSync.App.Media.ImageThumbnail.LoadForList(media, entry.ContentHash, decodePixelWidth: 128);
        }

        var isSourceKnown = !string.IsNullOrWhiteSpace(entry.SourceProcess);
        return new HistoryItemViewModel(
            entry.EventId,
            entry.Text,
            isSourceKnown ? entry.SourceProcess! : UnknownSourceLabel,
            entry.CreatedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture),
            isRemote,
            isRemote ? device?.DisplayName ?? UnknownRemoteLabel : Strings.History_LocalSource,
            // Unknown origins keep the quiet grey box; the neighbour hue belongs to a
            // device that is still in the paired list.
            device?.AccentIndex ?? DeviceAccent.None,
            // Render-time format tag (ADR 0003) — classified here, never persisted.
            // Images have no text body to classify; they stay Plain and use IsImage.
            entry.IsImage ? ClipContentFormat.Plain : ClipContentClassifier.Classify(entry.Text),
            entry.Kind,
            entry.MimeType,
            entry.EncodedBytes,
            entry.PixelWidth,
            entry.PixelHeight,
            entry.ContentHash,
            thumbnail,
            thumbnailImage,
            isSourceKnown,
            entry.IsLocalOnly);
    }
}
