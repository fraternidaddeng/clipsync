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
    System.Windows.Media.ImageSource? ThumbnailImage = null)
{
    /// <summary>Shown for remote clips whose origin device is no longer in the paired list.</summary>
    private const string UnknownRemoteLabel = "远端设备";

    public bool IsImage => string.Equals(Kind, "image", StringComparison.Ordinal);

    /// <summary>
    /// True only when the 56 px preview actually decoded. The list template keys
    /// its honest placeholder (无预览) off this — a load failure must never leave
    /// an unexplained empty box.
    /// </summary>
    public bool HasThumbnail => ThumbnailImage is not null;

    /// <summary>
    /// Card body line: the clip text, or for images a factual metadata line
    /// ("image/png 128×64 · 2.3 KiB") — pixels never masquerade as prose.
    /// </summary>
    public string Preview => IsImage ? FormatImagePreview() : Text;

    /// <summary>Badge text per format (ADR 0003 词汇); plain text carries no badge.</summary>
    public string FormatLabel => IsImage ? "图片" : Format switch
    {
        ClipContentFormat.Link => "链接",
        ClipContentFormat.Email => "账号",
        ClipContentFormat.Otp => "验证码",
        ClipContentFormat.Credential => "密码",
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
            thumbnail = ClipSync.App.Media.ImageThumbnail.Ensure(media, entry.ContentHash);
            if (thumbnail is not null)
            {
                // Decode once per refresh and freeze: the frozen bitmap is what the
                // list binds, so container recycling never re-runs a converter and a
                // transient file error can't blank an already-loaded row.
                thumbnailImage = ClipSync.App.Media.BitmapFile.TryLoad(thumbnail, decodePixelWidth: 128);
                if (thumbnailImage is null)
                {
                    ClipSync.App.Diagnostics.LocalDiagnostics.Write("thumbnail_bind_decode_failed");
                }
            }
        }

        return new HistoryItemViewModel(
            entry.EventId,
            entry.Text,
            entry.SourceProcess ?? "Unknown source",
            entry.CreatedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture),
            isRemote,
            isRemote ? device?.DisplayName ?? UnknownRemoteLabel : "本机",
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
            thumbnailImage);
    }

    private string FormatImagePreview()
    {
        if (PixelWidth is null || PixelHeight is null)
        {
            return string.IsNullOrEmpty(MimeType) ? "图片" : MimeType;
        }

        var size = EncodedBytes is null
            ? "?"
            : EncodedBytes.Value < 1024
                ? $"{EncodedBytes.Value} B"
                : $"{EncodedBytes.Value / 1024.0:0.#} KiB";
        return $"{MimeType ?? "图片"} {PixelWidth}×{PixelHeight} · {size}";
    }
}
