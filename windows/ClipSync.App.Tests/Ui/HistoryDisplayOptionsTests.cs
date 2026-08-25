using ClipSync.App.Ui;

namespace ClipSync.App.Tests.Ui;

/// <summary>
/// 显示 · 历史字号/预览行数（P0-1 / P1-7）: the pure key/store/size mapping. Stored
/// values follow the roadmap key contract — factors 0.9/1.0/1.15 and line counts
/// 2/4/6 — and anything unreadable falls back to 标准/4 without erroring.
/// </summary>
public sealed class HistoryDisplayOptionsTests
{
    [Theory]
    [InlineData(HistoryDisplayOptions.SmallScaleKey, 0.9)]
    [InlineData(HistoryDisplayOptions.StandardScaleKey, 1.0)]
    [InlineData(HistoryDisplayOptions.LargeScaleKey, 1.15)]
    public void ScaleKeysMapToTheRoadmapFactors(string key, double expected) =>
        Assert.Equal(expected, HistoryDisplayOptions.ScaleFor(key));

    [Theory]
    [InlineData("0.9", HistoryDisplayOptions.SmallScaleKey)]
    [InlineData("1.0", HistoryDisplayOptions.StandardScaleKey)]
    [InlineData("1.15", HistoryDisplayOptions.LargeScaleKey)]
    public void StoredFactorsRoundTripThroughTheirKeys(string stored, string expectedKey)
    {
        Assert.Equal(expectedKey, HistoryDisplayOptions.ScaleKeyForStored(stored));
        Assert.Equal(stored, HistoryDisplayOptions.StoredScaleFor(expectedKey));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("huge")]
    [InlineData("2.5")]
    [InlineData("not-a-number")]
    public void UnreadableStoredScaleFallsBackToStandard(string? stored) =>
        Assert.Equal(HistoryDisplayOptions.StandardScaleKey, HistoryDisplayOptions.ScaleKeyForStored(stored));

    [Theory]
    [InlineData("2", 2)]
    [InlineData("4", 4)]
    [InlineData("6", 6)]
    public void LineKeysMapToTheirCounts(string key, int expected)
    {
        Assert.Equal(expected, HistoryDisplayOptions.LinesFor(key));
        Assert.Equal(key, HistoryDisplayOptions.LinesKeyForStored(key));
        Assert.Equal(key, HistoryDisplayOptions.StoredLinesFor(key));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("3")]
    [InlineData("60")]
    [InlineData("lots")]
    public void UnreadableStoredLinesFallBackToFour(string? stored) =>
        Assert.Equal(HistoryDisplayOptions.DefaultLinesKey, HistoryDisplayOptions.LinesKeyForStored(stored));

    [Fact]
    public void StandardScaleKeepsTheCharterBaseSizes()
    {
        Assert.Equal(13, HistoryDisplayOptions.BodyFontSize(1.0));
        Assert.Equal(18, HistoryDisplayOptions.BodyLineHeight(1.0));
        Assert.Equal(13, HistoryDisplayOptions.DetailBodyFontSize(1.0));
        Assert.Equal(12, HistoryDisplayOptions.FlyoutFontSize(1.0));
        // The flyout keeps its pre-setting two-line cap (2 × 18 = the old MaxHeight 36).
        Assert.Equal(36, HistoryDisplayOptions.FlyoutMaxHeight(1.0));
    }

    [Fact]
    public void PreviewCapIsAnExactNumberOfLinesAtEveryScale()
    {
        foreach (var scale in new[] { 0.9, 1.0, 1.15 })
        {
            foreach (var lines in new[] { 2, 4, 6 })
            {
                Assert.Equal(
                    HistoryDisplayOptions.BodyLineHeight(scale) * lines,
                    HistoryDisplayOptions.PreviewMaxHeight(scale, lines));
            }
        }
    }

    [Fact]
    public void ContentSizesScaleWithTheFactor()
    {
        Assert.Equal(11.7, HistoryDisplayOptions.BodyFontSize(0.9));
        Assert.Equal(14.95, HistoryDisplayOptions.BodyFontSize(1.15));
        Assert.Equal(13.8, HistoryDisplayOptions.FlyoutFontSize(1.15));
        Assert.Equal(16.2, HistoryDisplayOptions.BodyLineHeight(0.9));
    }
}
