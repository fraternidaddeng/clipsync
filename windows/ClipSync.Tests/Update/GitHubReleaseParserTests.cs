using ClipSync.Core.Update;

namespace ClipSync.Tests.Update;

public sealed class GitHubReleaseParserTests
{
    private const string LatestJson =
        """
        {
          "tag_name": "v0.2.0",
          "html_url": "https://github.com/fraternidaddeng/clipsync/releases/tag/v0.2.0",
          "prerelease": false,
          "assets": [
            {
              "name": "ClipSync-android.apk",
              "browser_download_url": "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-android.apk",
              "size": 74724855,
              "digest": "sha256:9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5"
            },
            {
              "name": "ClipSync-android.apk.sha256",
              "browser_download_url": "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-android.apk.sha256",
              "size": 87
            },
            {
              "name": "ClipSync-windows-x64.zip",
              "browser_download_url": "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-windows-x64.zip",
              "size": 117596025,
              "digest": "sha256:1dbd5e336069839096200e9304a436bde1a727bd2fefe269cb00cff71a802981"
            },
            {
              "name": "ClipSync-windows-x64.zip.sha256",
              "browser_download_url": "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-windows-x64.zip.sha256",
              "size": 92
            }
          ]
        }
        """;

    [Fact]
    public void ParsesTagAssetsAndDigestsAndIgnoresUnknownFields()
    {
        var release = GitHubReleaseParser.Parse(LatestJson);
        Assert.Equal("v0.2.0", release.TagName);
        Assert.Equal("0.2.0", release.VersionLabel);
        Assert.Equal(4, release.Assets.Count);

        var apk = release.FindPayload(UpdatePlatform.Android);
        Assert.NotNull(apk);
        Assert.Equal("ClipSync-android.apk", apk.Name);
        Assert.Equal(74724855, apk.SizeBytes);
        Assert.Equal("9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5", apk.Sha256Hex);

        var zip = release.FindPayload(UpdatePlatform.Windows);
        Assert.NotNull(zip);
        Assert.Equal("ClipSync-windows-x64.zip", zip.Name);
        Assert.NotNull(release.FindSidecar(zip));
    }

    [Fact]
    public void DoesNotTreatTheUnsignedApkAsTheAndroidPayload()
    {
        var release = GitHubReleaseParser.Parse(
            """
            {
              "tag_name": "v0.1.0-rc.1",
              "assets": [
                {
                  "name": "ClipSync-android-unsigned.apk",
                  "browser_download_url": "https://example.test/unsigned.apk",
                  "size": 1
                }
              ]
            }
            """);
        Assert.Null(release.FindPayload(UpdatePlatform.Android));
    }

    [Fact]
    public void CheckResultIsAvailableOnlyWhenLatestRanksHigher()
    {
        var latest = GitHubReleaseParser.Parse(LatestJson);
        Assert.False(UpdateCheckResult.From("0.2.0", latest, UpdatePlatform.Windows).UpdateAvailable);
        Assert.False(UpdateCheckResult.From("0.3.0", latest, UpdatePlatform.Windows).UpdateAvailable);
        Assert.True(UpdateCheckResult.From("0.1.0-rc.2", latest, UpdatePlatform.Windows).UpdateAvailable);
        Assert.True(UpdateCheckResult.From("0.1.0", latest, UpdatePlatform.Android).UpdateAvailable);
    }

    [Theory]
    [InlineData("9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5 *ClipSync-android.apk\n")]
    [InlineData("9F59FF17B2CFEE9B623DF2A3F8A3BC636EFA173A36D7F412221F221CC24913B5  ClipSync-android.apk")]
    public void ParsesGnuAndStarSidecarBodies(string body)
    {
        Assert.Equal(
            "9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5",
            GitHubReleaseParser.ParseSha256Sidecar(body));
    }

    [Fact]
    public void SidecarWithAShortHashIsRejected()
    {
        Assert.Null(GitHubReleaseParser.ParseSha256Sidecar("abcd *file"));
    }

    [Fact]
    public void MissingTagIsAParseFailure()
    {
        Assert.Throws<FormatException>(() => GitHubReleaseParser.Parse("""{"assets":[]}"""));
    }
}
