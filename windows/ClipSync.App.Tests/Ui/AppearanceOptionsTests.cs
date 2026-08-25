using ClipSync.App.Ui;

namespace ClipSync.App.Tests.Ui;

/// <summary>
/// 外观（P1#6）: the pure key/store mapping for <c>ui_theme</c>. Stored values follow the
/// roadmap key contract — <c>system</c> / <c>day</c> / <c>night</c> — and anything
/// unreadable falls back to 跟随系统 without erroring.
/// </summary>
public sealed class AppearanceOptionsTests
{
    [Theory]
    [InlineData(AppearanceOptions.SystemKey)]
    [InlineData(AppearanceOptions.DayKey)]
    [InlineData(AppearanceOptions.NightKey)]
    public void ThemeKeysRoundTripThroughTheStore(string key)
    {
        Assert.Equal(key, AppearanceOptions.StoredFor(key));
        Assert.Equal(key, AppearanceOptions.KeyForStored(key));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("dark")]
    [InlineData("DAY")]
    [InlineData("auto")]
    public void UnreadableStoredThemeFallsBackToFollowSystem(string? stored) =>
        Assert.Equal(AppearanceOptions.SystemKey, AppearanceOptions.KeyForStored(stored));

    [Fact]
    public void TheDefaultIsFollowSystem() =>
        Assert.Equal(AppearanceOptions.SystemKey, AppearanceOptions.DefaultKey);
}
