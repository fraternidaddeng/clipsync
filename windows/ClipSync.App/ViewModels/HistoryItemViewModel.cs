using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

public sealed record HistoryItemViewModel(
    Guid EventId,
    string Text,
    string Source,
    string CreatedAt)
{
    public static HistoryItemViewModel FromEntry(ClipboardHistoryEntry entry) => new(
        entry.EventId,
        entry.Text,
        entry.SourceProcess ?? "Unknown source",
        entry.CreatedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture));
}
