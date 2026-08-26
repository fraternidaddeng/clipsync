using System.IO;

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
///   <item>whatever <c>adb</c>/<c>adb.exe</c> is already on <c>PATH</c>.</item>
/// </list>
/// Returns null when none is found — the UI then explains how to get adb rather than failing
/// obscurely.
/// </summary>
public static class AdbLocator
{
    private const string ExecutableWindows = "adb.exe";
    private const string ExecutableUnix = "adb";

    public static string? Locate()
    {
        foreach (var candidate in CandidatePaths())
        {
            if (!string.IsNullOrWhiteSpace(candidate) && File.Exists(candidate))
            {
                return candidate;
            }
        }

        return FindOnPath();
    }

    private static IEnumerable<string> CandidatePaths()
    {
        var appDir = AppContext.BaseDirectory;
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
