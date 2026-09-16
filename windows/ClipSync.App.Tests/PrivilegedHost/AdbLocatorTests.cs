using System.IO;
using ClipSync.App.PrivilegedHost;

namespace ClipSync.App.Tests.PrivilegedHost;

/// <summary>
/// Checkout-local adb discovery: Debug runs live under <c>windows/…/bin/…</c> and the
/// platform-tools live at the repo's <c>.tools/android-sdk</c> or the path in
/// <c>android/local.properties</c>. Isolated from PATH / ANDROID_* so a machine-wide SDK
/// cannot steal the assertion.
/// </summary>
public sealed class AdbLocatorTests : IDisposable
{
    private readonly string root;

    public AdbLocatorTests()
    {
        root = Path.Combine(Path.GetTempPath(), "clipsync-adb-locator-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
    }

    [Fact]
    public void LocateInAncestorsFindsRepoToolsSdkSixLevelsUp()
    {
        var appDir = Path.Combine(root, "windows", "ClipSync.App", "bin", "Debug", "net8.0-windows10.0.19041.0");
        Directory.CreateDirectory(appDir);
        var adb = WriteDummyAdb(Path.Combine(root, ".tools", "android-sdk", "platform-tools"));

        Assert.Equal(adb, AdbLocator.LocateInAncestors(appDir));
    }

    [Fact]
    public void LocateInAncestorsReadsGradleLocalProperties()
    {
        var appDir = Path.Combine(root, "windows", "ClipSync.App", "bin", "Debug", "net8");
        Directory.CreateDirectory(appDir);
        var sdk = Path.Combine(root, "external-sdk");
        var adb = WriteDummyAdb(Path.Combine(sdk, "platform-tools"));
        var androidDir = Path.Combine(root, "android");
        Directory.CreateDirectory(androidDir);
        // Gradle-escaped Windows path, the form android/local.properties actually writes.
        File.WriteAllText(
            Path.Combine(androidDir, "local.properties"),
            "sdk.dir=" + sdk.Replace("\\", "\\\\") + Environment.NewLine);

        Assert.Equal(adb, AdbLocator.LocateInAncestors(appDir));
    }

    [Fact]
    public void TryReadSdkDirUnescapesGradleWindowsPath()
    {
        var path = Path.Combine(root, "local.properties");
        File.WriteAllText(path, "sdk.dir=D\\:\\\\paste-tools\\\\android-sdk" + Environment.NewLine);

        Assert.Equal(@"D:\paste-tools\android-sdk", AdbLocator.TryReadSdkDir(path));
    }

    [Fact]
    public void TryReadSdkDirKeepsAnUnescapedWindowsPath()
    {
        var path = Path.Combine(root, "local.properties");
        File.WriteAllText(path, @"sdk.dir=D:\paste-tools\android-sdk" + Environment.NewLine);

        Assert.Equal(@"D:\paste-tools\android-sdk", AdbLocator.TryReadSdkDir(path));
    }

    [Fact]
    public void LocateInAncestorsReturnsNullWhenNothingIsThere()
    {
        var appDir = Path.Combine(root, "nowhere");
        Directory.CreateDirectory(appDir);

        Assert.Null(AdbLocator.LocateInAncestors(appDir));
    }

    private static string WriteDummyAdb(string platformTools)
    {
        Directory.CreateDirectory(platformTools);
        var adb = Path.Combine(platformTools, "adb.exe");
        File.WriteAllText(adb, "dummy");
        return adb;
    }

    public void Dispose()
    {
        if (Directory.Exists(root))
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
