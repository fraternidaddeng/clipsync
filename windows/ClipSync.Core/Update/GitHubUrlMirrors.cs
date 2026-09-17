namespace ClipSync.Core.Update;

/// <summary>
/// GitHub first, then China-reachable URL-prefix proxies. Only
/// <c>github.com</c> / <c>api.github.com</c> / <c>*.githubusercontent.com</c>
/// are rewritten; injected test hosts stay as-is so scripted clients keep a
/// single GET.
/// </summary>
public static class GitHubUrlMirrors
{
    public static readonly string[] Prefixes =
    [
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://mirror.ghproxy.com/",
    ];

    public static IReadOnlyList<string> Candidates(string url)
    {
        var trimmed = url.Trim();
        if (trimmed.Length == 0)
        {
            return [];
        }

        if (Prefixes.Any(prefix => trimmed.StartsWith(prefix, StringComparison.Ordinal)))
        {
            return [trimmed];
        }

        if (!IsMirrorable(trimmed))
        {
            return [trimmed];
        }

        var list = new List<string>(1 + Prefixes.Length) { trimmed };
        foreach (var prefix in Prefixes)
        {
            list.Add(prefix + trimmed);
        }

        return list;
    }

    public static bool IsMirrorable(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Host is null)
        {
            return false;
        }

        var host = uri.Host;
        return host.Equals("github.com", StringComparison.OrdinalIgnoreCase)
            || host.Equals("api.github.com", StringComparison.OrdinalIgnoreCase)
            || host.EndsWith(".githubusercontent.com", StringComparison.OrdinalIgnoreCase);
    }
}
