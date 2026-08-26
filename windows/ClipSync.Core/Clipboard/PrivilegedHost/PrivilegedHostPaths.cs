namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// The Android-side coordinates the PC needs to help a paired phone open its 特权直读
/// (privileged read) channel over adb. These mirror the Android app's
/// <c>PrivilegedHostConstants</c> / <c>PrivilegedHostScript</c>: the package writes a
/// world-readable <c>start.sh</c> next to its external files dir, and an adb/root shell
/// runs it to spawn the in-APK privileged host (process <c>clipsync_priv_server</c>).
/// Nothing here raises privileges on its own; the shell user already has them.
/// </summary>
public static class PrivilegedHostPaths
{
    /// <summary>The Android application id; the same string is the adb shell package target.</summary>
    public const string PackageName = "com.clipsync.android";

    /// <summary>The bundled privileged host's process nice-name, used to detect a live channel.</summary>
    public const string HostProcessName = "clipsync_priv_server";

    /// <summary>File the phone materializes for operators; consumed by the adb/root shell.</summary>
    public const string ScriptFileName = "start.sh";

    /// <summary>Absolute on-device path of the start script (external files dir of the package).</summary>
    public const string ScriptPath =
        "/storage/emulated/0/Android/data/" + PackageName + "/" + ScriptFileName;

    /// <summary>
    /// The adb shell arguments that run the start script. Passed to <c>adb [-s serial] …</c>;
    /// keeping this as discrete tokens avoids any shell-quoting ambiguity on the PC side.
    /// </summary>
    public static IReadOnlyList<string> StartScriptShellArguments { get; } =
        new[] { "shell", "sh", ScriptPath };

    /// <summary>
    /// The adb shell arguments that report whether the privileged host is currently running.
    /// <c>pgrep -f</c> prints the pid(s) when a match exists and nothing when it does not, so a
    /// non-empty stdout means the channel is live. This is a read-only probe — it never starts
    /// or stops anything.
    /// </summary>
    public static IReadOnlyList<string> HostRunningProbeShellArguments { get; } =
        new[] { "shell", "pgrep", "-f", HostProcessName };
}
