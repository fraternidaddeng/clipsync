using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

public sealed record HistoryItemViewModel(
    Guid EventId,
    string Text,
    string Source,
    string CreatedAt,
    bool IsRemote,
    string OriginLabel)
{
    /// <summary>Shown for remote clips whose origin device is no longer in the paired list.</summary>
    private const string UnknownRemoteLabel = "远端设备";

    public static HistoryItemViewModel FromEntry(
        ClipboardHistoryEntry entry,
        string localDeviceId,
        Func<string, string?>? deviceNameLookup = null)
    {
        var isRemote = !string.Equals(entry.OriginDeviceId, localDeviceId, StringComparison.Ordinal);
        return new HistoryItemViewModel(
            entry.EventId,
            entry.Text,
            entry.SourceProcess ?? "Unknown source",
            entry.CreatedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture),
            isRemote,
            isRemote ? deviceNameLookup?.Invoke(entry.OriginDeviceId) ?? UnknownRemoteLabel : "本机");
    }
}
