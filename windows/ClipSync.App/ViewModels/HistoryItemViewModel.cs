using ClipSync.App.Ui;
using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

public sealed record HistoryItemViewModel(
    Guid EventId,
    string Text,
    string Source,
    string CreatedAt,
    bool IsRemote,
    string OriginLabel,
    int OriginAccentIndex)
{
    /// <summary>Shown for remote clips whose origin device is no longer in the paired list.</summary>
    private const string UnknownRemoteLabel = "远端设备";

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
            device?.AccentIndex ?? DeviceAccent.None);
    }
}
