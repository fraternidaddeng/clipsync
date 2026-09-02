using ClipSync.Core.Update;

namespace ClipSync.Tests.Update;

public sealed class AppVersionTests
{
    [Theory]
    [InlineData("0.2.0", 0, 2, 0, 99)]
    [InlineData("v0.2.0", 0, 2, 0, 99)]
    [InlineData("0.1.0-rc.2", 0, 1, 0, 2)]
    [InlineData("v0.1.0-rc.1", 0, 1, 0, 1)]
    [InlineData("0.3.0+deadbeef", 0, 3, 0, 99)]
    public void ParsesReleaseTagsAndInformationalVersions(
        string raw,
        int major,
        int minor,
        int patch,
        int offset)
    {
        Assert.True(AppVersion.TryParse(raw, out var version));
        Assert.Equal(new AppVersion(major, minor, patch, offset), version);
    }

    [Theory]
    [InlineData("")]
    [InlineData("1.0")]
    [InlineData("0.2.0-beta.1")]
    [InlineData("0.2.0-rc.0")]
    [InlineData("0.2.0-rc.99")]
    public void RejectsShapesTheReleaseScriptsWouldAlsoReject(string raw)
    {
        Assert.False(AppVersion.TryParse(raw, out _));
    }

    [Fact]
    public void RankMatchesTheAndroidVersionCodeScheme()
    {
        // package-android.ps1: 0.1.0-rc.1 → 10001, 0.1.0 → 10099, 0.2.0 → 20099.
        Assert.Equal(10001, AppVersion.Parse("0.1.0-rc.1").Rank);
        Assert.Equal(10002, AppVersion.Parse("0.1.0-rc.2").Rank);
        Assert.Equal(10099, AppVersion.Parse("0.1.0").Rank);
        Assert.Equal(20099, AppVersion.Parse("0.2.0").Rank);
        Assert.Equal(30099, AppVersion.Parse("0.3.0").Rank);
    }

    [Fact]
    public void RcSortsBelowItsFinalAndAboveThePreviousPatch()
    {
        Assert.True(AppVersion.Compare("0.1.0-rc.2", "0.1.0") < 0);
        Assert.True(AppVersion.Compare("0.1.0", "0.2.0-rc.1") < 0);
        Assert.True(AppVersion.Compare("0.2.0", "0.2.0") == 0);
        Assert.True(AppVersion.Compare("v0.2.0", "0.3.0") < 0);
    }

    [Fact]
    public void UnparseableLocalVersionLosesToARealRelease()
    {
        Assert.True(AppVersion.Compare("dev", "0.2.0") < 0);
        Assert.True(AppVersion.Compare("0.2.0", "???") > 0);
    }
}
