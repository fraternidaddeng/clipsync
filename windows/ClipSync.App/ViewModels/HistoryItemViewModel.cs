using ClipSync.App.Ui;
using ClipSync.Core.Clipboard;
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
    ClipContentFormat Format)
{
    /// <summary>Shown for remote clips whose origin device is no longer in the paired list.</summary>
    private const string UnknownRemoteLabel = "远端设备";

    /// <summary>Badge text per format (ADR 0003 词汇); plain text carries no badge.</summary>
    public string FormatLabel => Format switch
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
    public int FormatAccentIndex => Format switch
    {
        ClipContentFormat.Email => 1,      // 青灰
        ClipContentFormat.Link => 2,       // 水蓝
        ClipContentFormat.Otp => 3,        // 蓝紫
        ClipContentFormat.Credential => 4, // 藕紫
        _ => DeviceAccent.None,
    };

    /// <summary>A quiet card is the default: only non-plain formats show a badge.</summary>
    public bool HasFormatBadge => Format != ClipContentFormat.Plain;

    public static HistoryItemViewModel FromEntry(
        ClipboardHistoryEntry entry,
        string localDeviceId,
        Func<string, PairedDeviceViewModel?>? deviceLookup = null)
    {
        var isRemote = !string.Equals(entry.OriginDeviceId, localDeviceId, StringComparison.Ordinal);
        var device = isRemote ? deviceLookup?.Invoke(entry.OriginDeviceId) : null;
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
            ClipContentClassifier.Classify(entry.Text));
    }
}
