using ClipSync.Core.Update;

namespace ClipSync.App.Docs;

/// <summary>
/// Where the 使用前必读 (privacy and risks) document lives for a given UI culture: the same
/// GitHub repository the in-app updater trusts, one edition per translated language. Chinese
/// in any script or region reads the zh-CN edition, Japanese the ja edition, everything else
/// the English default — the same rule as the Android side's <c>privacyDocUrl</c>.
/// </summary>
public static class PrivacyDocLink
{
    public static readonly Uri DocsBase = new(
        $"https://github.com/{GitHubReleaseClient.DefaultOwner}/{GitHubReleaseClient.DefaultRepo}/blob/main/docs/");

    private const string FileStem = "privacy-and-risks";

    public static Uri For(string? cultureName)
    {
        var language = (cultureName ?? string.Empty).Split('-', '_')[0].ToLowerInvariant();
        var suffix = language switch
        {
            "zh" => ".zh-CN.md",
            "ja" => ".ja.md",
            _ => ".md",
        };
        return new Uri(DocsBase, FileStem + suffix);
    }
}
