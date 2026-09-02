using System.Net.Http.Headers;
using System.Security.Cryptography;

namespace ClipSync.Core.Update;

/// <summary>
/// Fetches <c>/repos/{owner}/{repo}/releases/latest</c> and downloads a named
/// asset. The client never applies an update — that is a host concern (WPF
/// restart vs Android package installer).
/// </summary>
public sealed class GitHubReleaseClient : IDisposable
{
    public const string DefaultOwner = "fraternidaddeng";
    public const string DefaultRepo = "clipsync";
    public const string LatestPath = "/releases/latest";

    private readonly HttpClient http;
    private readonly bool ownsHttp;
    private readonly Uri latestUri;

    public GitHubReleaseClient(
        string? currentVersion = null,
        HttpMessageHandler? handler = null,
        Uri? latestUri = null)
    {
        this.latestUri = latestUri ?? new Uri(
            $"https://api.github.com/repos/{DefaultOwner}/{DefaultRepo}{LatestPath}");
        if (handler is null)
        {
            http = new HttpClient { Timeout = TimeSpan.FromMinutes(15) };
            ownsHttp = true;
        }
        else
        {
            http = new HttpClient(handler, disposeHandler: false) { Timeout = TimeSpan.FromMinutes(15) };
            ownsHttp = true;
        }

        var product = string.IsNullOrWhiteSpace(currentVersion) ? "ClipSync" : $"ClipSync/{currentVersion}";
        http.DefaultRequestHeaders.UserAgent.ParseAdd($"{product} (+https://github.com/{DefaultOwner}/{DefaultRepo})");
        http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
    }

    public async Task<GitHubLatestRelease> FetchLatestAsync(CancellationToken cancellationToken = default)
    {
        using var response = await http.GetAsync(latestUri, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException(
                $"GitHub latest release returned {(int)response.StatusCode}: {TrimForError(body)}");
        }

        return GitHubReleaseParser.Parse(body);
    }

    /// <summary>
    /// Resolves the SHA-256 for <paramref name="payload"/>: prefer the API
    /// <c>digest</c>, otherwise download the <c>.sha256</c> sidecar. Missing
    /// both is a hard failure — the host must not install an unverified file.
    /// </summary>
    public async Task<string> ResolveSha256Async(
        GitHubLatestRelease release,
        ReleaseAsset payload,
        CancellationToken cancellationToken = default)
    {
        if (!string.IsNullOrWhiteSpace(payload.Sha256Hex)
            && GitHubReleaseParser.IsSha256Hex(payload.Sha256Hex))
        {
            return payload.Sha256Hex;
        }

        var sidecar = release.FindSidecar(payload)
            ?? throw new InvalidOperationException(
                $"Release {release.TagName} has no SHA-256 for '{payload.Name}'.");
        using var response = await http.GetAsync(new Uri(sidecar.BrowserDownloadUrl), cancellationToken)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var text = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        return GitHubReleaseParser.ParseSha256Sidecar(text)
            ?? throw new InvalidOperationException($"Could not parse SHA-256 sidecar for '{payload.Name}'.");
    }

    public async Task DownloadAsync(
        ReleaseAsset asset,
        Stream destination,
        IProgress<UpdateDownloadProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        using var response = await http.GetAsync(
                new Uri(asset.BrowserDownloadUrl),
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var total = response.Content.Headers.ContentLength ?? asset.SizeBytes;
        await using var source = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        var buffer = new byte[81_920];
        long received = 0;
        int read;
        while ((read = await source.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken)
                   .ConfigureAwait(false)) > 0)
        {
            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            received += read;
            progress?.Report(new UpdateDownloadProgress(received, total));
        }
    }

    public static string ComputeSha256Hex(Stream stream)
    {
        if (stream.CanSeek)
        {
            stream.Position = 0;
        }

        var hash = SHA256.HashData(stream);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    public static void VerifySha256(Stream stream, string expectedHex)
    {
        var actual = ComputeSha256Hex(stream);
        if (!string.Equals(actual, expectedHex, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException(
                $"SHA-256 mismatch (expected {expectedHex}, got {actual}).");
        }
    }

    public void Dispose()
    {
        if (ownsHttp)
        {
            http.Dispose();
        }
    }

    private static string TrimForError(string body)
    {
        var trimmed = body.Trim();
        return trimmed.Length <= 180 ? trimmed : trimmed[..180] + "…";
    }
}

public readonly record struct UpdateDownloadProgress(long ReceivedBytes, long TotalBytes)
{
    public int Percent =>
        TotalBytes > 0 ? (int)Math.Clamp(ReceivedBytes * 100 / TotalBytes, 0, 100) : 0;
}

/// <summary>The host-facing decision after comparing local version to latest.</summary>
public sealed record UpdateCheckResult(
    string CurrentVersion,
    GitHubLatestRelease Latest,
    ReleaseAsset? Payload,
    bool UpdateAvailable)
{
    public static UpdateCheckResult From(
        string currentVersion,
        GitHubLatestRelease latest,
        UpdatePlatform platform)
    {
        var payload = latest.FindPayload(platform);
        var available = payload is not null
            && AppVersion.Compare(currentVersion, latest.VersionLabel) < 0;
        return new UpdateCheckResult(currentVersion, latest, payload, available);
    }
}
