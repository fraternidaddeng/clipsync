using System.Text.Json;

namespace ClipSync.Core.Update;

/// <summary>The platform asset the updater knows how to apply.</summary>
public enum UpdatePlatform
{
    Windows,
    Android,
}

/// <summary>
/// One published file on a GitHub Release. <see cref="Sha256Hex"/> is the
/// lowercase hex digest when GitHub (or the sidecar) supplied one; null means
/// the updater must fetch the <c>.sha256</c> sidecar or refuse to install.
/// </summary>
public sealed record ReleaseAsset(
    string Name,
    string BrowserDownloadUrl,
    long SizeBytes,
    string? Sha256Hex);

/// <summary>The latest non-prerelease GitHub Release, already mapped to our assets.</summary>
public sealed record GitHubLatestRelease(
    string TagName,
    string HtmlUrl,
    IReadOnlyList<ReleaseAsset> Assets)
{
    public string VersionLabel
    {
        get
        {
            var tag = TagName.Trim();
            return tag.StartsWith('v') || tag.StartsWith('V') ? tag[1..] : tag;
        }
    }

    public ReleaseAsset? FindPayload(UpdatePlatform platform)
    {
        var expected = platform switch
        {
            UpdatePlatform.Windows => "ClipSync-windows-x64.zip",
            UpdatePlatform.Android => "ClipSync-android.apk",
            _ => null,
        };
        if (expected is null)
        {
            return null;
        }

        return Assets.FirstOrDefault(asset =>
            string.Equals(asset.Name, expected, StringComparison.OrdinalIgnoreCase));
    }

    public ReleaseAsset? FindSidecar(ReleaseAsset payload) =>
        Assets.FirstOrDefault(asset =>
            string.Equals(asset.Name, payload.Name + ".sha256", StringComparison.OrdinalIgnoreCase));
}

/// <summary>Parses GitHub's <c>/releases/latest</c> JSON. Unknown fields are ignored.</summary>
public static class GitHubReleaseParser
{
    public static GitHubLatestRelease Parse(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
        {
            throw new FormatException("GitHub latest release body is not an object.");
        }

        var tag = RequiredString(root, "tag_name");
        var htmlUrl = OptionalString(root, "html_url") ?? string.Empty;
        var assets = new List<ReleaseAsset>();
        if (root.TryGetProperty("assets", out var assetsElement)
            && assetsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in assetsElement.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Object)
                {
                    continue;
                }

                var name = OptionalString(item, "name");
                var url = OptionalString(item, "browser_download_url");
                if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(url))
                {
                    continue;
                }

                var size = 0L;
                if (item.TryGetProperty("size", out var sizeElement)
                    && sizeElement.ValueKind == JsonValueKind.Number
                    && sizeElement.TryGetInt64(out var parsedSize)
                    && parsedSize >= 0)
                {
                    size = parsedSize;
                }

                assets.Add(new ReleaseAsset(name, url, size, ReadSha256(item)));
            }
        }

        return new GitHubLatestRelease(tag, htmlUrl, assets);
    }

    /// <summary>
    /// Sidecar body is <c>hex *filename</c> or <c>hex  filename</c> (GNU coreutils /
    /// this repo's <c>package-*.ps1</c> style). Returns lowercase hex or null.
    /// </summary>
    public static string? ParseSha256Sidecar(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        var token = text.Trim().Split((char[]?)null, 2, StringSplitOptions.RemoveEmptyEntries);
        if (token.Length == 0)
        {
            return null;
        }

        var hex = token[0].Trim().TrimStart('*');
        return IsSha256Hex(hex) ? hex.ToLowerInvariant() : null;
    }

    private static string? ReadSha256(JsonElement asset)
    {
        if (!asset.TryGetProperty("digest", out var digest) || digest.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        var value = digest.GetString();
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        const string prefix = "sha256:";
        if (value.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            var hex = value[prefix.Length..].Trim();
            return IsSha256Hex(hex) ? hex.ToLowerInvariant() : null;
        }

        return IsSha256Hex(value) ? value.Trim().ToLowerInvariant() : null;
    }

    internal static bool IsSha256Hex(string? hex) =>
        hex is { Length: 64 } && hex.All(static c => Uri.IsHexDigit(c));

    private static string RequiredString(JsonElement obj, string name)
    {
        var value = OptionalString(obj, name);
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new FormatException($"GitHub latest release is missing '{name}'.");
        }

        return value;
    }

    private static string? OptionalString(JsonElement obj, string name)
    {
        if (!obj.TryGetProperty(name, out var element) || element.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        return element.GetString();
    }
}
