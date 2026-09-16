using System.IO;
using System.Text;

namespace ClipSync.App.PrivilegedHost;

/// <summary>
/// Finds the adb executable without ever installing anything silently. Search order, most
/// specific first:
/// <list type="number">
///   <item>a <c>platform-tools\adb.exe</c> shipped next to the app (a packager may drop the
///   Google platform-tools folder there so the feature works with zero user setup);</item>
///   <item>the <c>CLIPSYNC_ADB_PATH</c> override, for unusual installs;</item>
///   <item>the standard Android SDK locations (<c>ANDROID_SDK_ROOT</c> / <c>ANDROID_HOME</c>,
///   then <c>%LOCALAPPDATA%\Android\Sdk\platform-tools</c>);</item>
///   <item>a repo-local SDK walked from the app directory (Debug runs live under
///   <c>windows/…/bin/…</c> and the platform-tools live at the checkout's
///   <c>.tools/android-sdk</c> or the path in <c>android/local.properties</c>);</item>
///   <item>whatever <c>adb</c>/<c>adb.exe</c> is already on <c>PATH</c>.</item>
/// </list>
/// Returns null when none is found — the UI then explains how to get adb rather than failing
/// obscurely.
/// </summary>
public static class AdbLocator
{
    private const string ExecutableWindows = "adb.exe";
    private const string ExecutableUnix = "adb";

    /// <summary>
    /// How far to walk toward the drive root looking for a checkout-local SDK. Debug
    /// <c>BaseDirectory</c> is six levels below the repo root; eight leaves a little slack
    /// without scanning the whole volume.
    /// </summary>
    internal const int MaxAncestorWalk = 8;

    public static string? Locate() => Locate(AppContext.BaseDirectory);

    /// <summary>
    /// Same search as <see cref="Locate()"/>, starting the ancestor walk from
    /// <paramref name="baseDirectory"/> instead of the running app folder. Tests pass a
    /// fake tree; production passes <see cref="AppContext.BaseDirectory"/>.
    /// </summary>
    public static string? Locate(string? baseDirectory)
    {
        foreach (var candidate in CandidatePaths(baseDirectory))
        {
            if (!string.IsNullOrWhiteSpace(candidate) && File.Exists(candidate))
            {
                return candidate;
            }
        }

        return FindOnPath();
    }

    /// <summary>
    /// The checkout-local half of the search, isolated so tests can assert the walk without
    /// a machine-wide SDK or <c>PATH</c> entry stealing the result.
    /// </summary>
    internal static string? LocateInAncestors(string startDirectory)
    {
        foreach (var candidate in AncestorAdbCandidates(startDirectory))
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return null;
    }

    /// <summary>
    /// Reads <c>sdk.dir</c> from a Gradle <c>local.properties</c> file. Escapes that Gradle
    /// writes for Windows paths (<c>D\:\\sdk</c>, <c>D:\\sdk</c>) are unescaped; a path
    /// typed with ordinary single backslashes is left alone.
    /// </summary>
    internal static string? TryReadSdkDir(string propertiesPath)
    {
        foreach (var raw in File.ReadLines(propertiesPath))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line[0] is '#' or '!')
            {
                continue;
            }

            const string prefix = "sdk.dir=";
            if (!line.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var value = UnescapeSdkDir(line[prefix.Length..].Trim());
            if (value.Length >= 2 && value[0] == '"' && value[^1] == '"')
            {
                value = value[1..^1];
            }

            if (string.IsNullOrWhiteSpace(value))
            {
                return null;
            }

            if (!Path.IsPathRooted(value))
            {
                var androidDir = Path.GetDirectoryName(propertiesPath);
                var repoRoot = string.IsNullOrEmpty(androidDir) ? null : Path.GetDirectoryName(androidDir);
                value = Path.GetFullPath(Path.Combine(repoRoot ?? string.Empty, value));
            }

            return value;
        }

        return null;
    }

    private static IEnumerable<string> CandidatePaths(string? baseDirectory)
    {
        var appDir = string.IsNullOrWhiteSpace(baseDirectory)
            ? AppContext.BaseDirectory
            : baseDirectory;
        yield return Path.Combine(appDir, "platform-tools", ExecutableWindows);
        yield return Path.Combine(appDir, "platform-tools", ExecutableUnix);

        var overridePath = Environment.GetEnvironmentVariable("CLIPSYNC_ADB_PATH");
        if (!string.IsNullOrWhiteSpace(overridePath))
        {
            yield return overridePath;
        }

        foreach (var root in SdkRoots())
        {
            yield return Path.Combine(root, "platform-tools", ExecutableWindows);
            yield return Path.Combine(root, "platform-tools", ExecutableUnix);
        }

        foreach (var candidate in AncestorAdbCandidates(appDir))
        {
            yield return candidate;
        }
    }

    private static IEnumerable<string> SdkRoots()
    {
        var sdkRoot = Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
        if (!string.IsNullOrWhiteSpace(sdkRoot))
        {
            yield return sdkRoot;
        }

        var androidHome = Environment.GetEnvironmentVariable("ANDROID_HOME");
        if (!string.IsNullOrWhiteSpace(androidHome))
        {
            yield return androidHome;
        }

        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (!string.IsNullOrWhiteSpace(localAppData))
        {
            yield return Path.Combine(localAppData, "Android", "Sdk");
        }
    }

    private static IEnumerable<string> AncestorAdbCandidates(string startDirectory)
    {
        string? dir;
        try
        {
            dir = Path.GetFullPath(startDirectory);
        }
        catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
        {
            yield break;
        }

        for (var i = 0; i < MaxAncestorWalk && dir is not null; i++)
        {
            var toolsSdk = Path.Combine(dir, ".tools", "android-sdk");
            yield return Path.Combine(toolsSdk, "platform-tools", ExecutableWindows);
            yield return Path.Combine(toolsSdk, "platform-tools", ExecutableUnix);

            var properties = Path.Combine(dir, "android", "local.properties");
            if (File.Exists(properties))
            {
                var sdkDir = TryReadSdkDir(properties);
                if (!string.IsNullOrWhiteSpace(sdkDir))
                {
                    yield return Path.Combine(sdkDir, "platform-tools", ExecutableWindows);
                    yield return Path.Combine(sdkDir, "platform-tools", ExecutableUnix);
                }
            }

            dir = Directory.GetParent(dir)?.FullName;
        }
    }

    private static string UnescapeSdkDir(string value)
    {
        // Java properties: \\ \: \= become \, :, =. A lone \ before an ordinary
        // letter is kept so D:\sdk (no Gradle escaping) still resolves.
        var builder = new StringBuilder(value.Length);
        for (var i = 0; i < value.Length; i++)
        {
            if (value[i] == '\\' && i + 1 < value.Length)
            {
                var next = value[i + 1];
                if (next is '\\' or ':' or '=' or '#' or '!' or ' ')
                {
                    builder.Append(next);
                    i++;
                    continue;
                }
            }

            builder.Append(value[i]);
        }

        return builder.ToString();
    }

    private static string? FindOnPath()
    {
        var pathVar = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrWhiteSpace(pathVar))
        {
            return null;
        }

        foreach (var dir in pathVar.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            foreach (var name in new[] { ExecutableWindows, ExecutableUnix })
            {
                var candidate = Path.Combine(dir.Trim(), name);
                if (File.Exists(candidate))
                {
                    return candidate;
                }
            }
        }

        return null;
    }
}
