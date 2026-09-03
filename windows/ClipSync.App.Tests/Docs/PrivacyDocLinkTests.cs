using ClipSync.App.Docs;

namespace ClipSync.App.Tests.Docs;

/// <summary>使用前必读 link: one edition per translated language, English for everything else, hosted in the updater's repository.</summary>
public sealed class PrivacyDocLinkTests
{
    private const string Base = "https://github.com/fraternidaddeng/clipsync/blob/main/docs/";

    [Theory]
    [InlineData("zh-Hans", Base + "privacy-and-risks.zh-CN.md")]
    [InlineData("zh-Hant", Base + "privacy-and-risks.zh-CN.md")]
    [InlineData("zh-CN", Base + "privacy-and-risks.zh-CN.md")]
    [InlineData("ja", Base + "privacy-and-risks.ja.md")]
    [InlineData("ja-JP", Base + "privacy-and-risks.ja.md")]
    [InlineData("en", Base + "privacy-and-risks.md")]
    [InlineData("en-US", Base + "privacy-and-risks.md")]
    [InlineData("de", Base + "privacy-and-risks.md")]
    [InlineData("", Base + "privacy-and-risks.md")]
    [InlineData(null, Base + "privacy-and-risks.md")]
    public void PicksTheEditionByLanguage(string? culture, string expected)
    {
        Assert.Equal(expected, PrivacyDocLink.For(culture).AbsoluteUri);
    }

    [Fact]
    public void BaseComesFromTheUpdaterConstantsNotASecondCopy()
    {
        Assert.Contains(
            $"/{ClipSync.Core.Update.GitHubReleaseClient.DefaultOwner}/{ClipSync.Core.Update.GitHubReleaseClient.DefaultRepo}/",
            PrivacyDocLink.DocsBase.AbsoluteUri,
            StringComparison.Ordinal);
    }
}
