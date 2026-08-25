using ClipSync.App.ViewModels;
using ClipSync.Core.Storage;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// Source-app display contract for the history list: a resolved process stays in
/// the quiet mono metadata line ("source · time"), while an unresolved source
/// becomes the 未知来源 grey annotation box — never bare untranslated text.
/// </summary>
public sealed class HistoryItemSourceTests
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    [Fact]
    public void KnownSourceStaysInTheMetadataLine()
    {
        var item = HistoryItemViewModel.FromEntry(TextEntry("chrome"), LocalDeviceId);

        Assert.True(item.IsSourceKnown);
        Assert.Equal("chrome", item.Source);
        Assert.Equal($"chrome · {item.CreatedAt}", item.MetaLine);
    }

    [Fact]
    public void MissingSourceBecomesTheUnknownSourceBox()
    {
        var item = HistoryItemViewModel.FromEntry(TextEntry(null), LocalDeviceId);

        Assert.False(item.IsSourceKnown);
        Assert.Equal("未知来源", item.Source);
        // The box carries the label, so the metadata line keeps only the time.
        Assert.Equal(item.CreatedAt, item.MetaLine);
    }

    [Fact]
    public void WhitespaceSourceCountsAsUnknown()
    {
        var item = HistoryItemViewModel.FromEntry(TextEntry("   "), LocalDeviceId);

        Assert.False(item.IsSourceKnown);
        Assert.Equal("未知来源", item.Source);
    }

    [Fact]
    public void TextRowsNeverShowTheNoPreviewBadge()
    {
        var item = HistoryItemViewModel.FromEntry(TextEntry("notepad"), LocalDeviceId);

        Assert.False(item.IsImage);
        Assert.False(item.ShowsNoPreview);
        Assert.False(item.HasThumbnail);
    }

    private static ClipboardHistoryEntry TextEntry(string? sourceProcess) => new(
        Guid.NewGuid(),
        LocalDeviceId,
        1,
        "hello clipboard",
        new string('a', 64),
        sourceProcess,
        DateTimeOffset.UtcNow,
        null,
        null);
}
