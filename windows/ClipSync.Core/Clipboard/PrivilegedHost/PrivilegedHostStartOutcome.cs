namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>How the on-phone start script resolved, as read back from its own stdout/stderr.</summary>
public enum PrivilegedHostStartStatus
{
    /// <summary>The script printed "info: spawned" and a follow-up check saw the host process running.</summary>
    Started,

    /// <summary>The script ran but exited with a "fatal:" line (wrong uid, apk not found, …).</summary>
    ScriptFailed,

    /// <summary>adb itself failed before or while running the script (no device, transport error).</summary>
    AdbFailed,

    /// <summary>
    /// The script reported it spawned the host, but a retried read-only check never saw the host
    /// process running and did see it definitely absent — it launched and died right away (a
    /// wrong-uid/apk edge that "info: spawned" is printed before, or the wireless transport
    /// dropping mid-launch). Reported honestly rather than as the rosy "已发送启动命令".
    /// </summary>
    SpawnedButNotDetected,
}

/// <summary>
/// The parsed result of asking a phone to start its 特权直读 host. The reason is a short,
/// stable token or the script's own "fatal:" text — never clipboard content and never a
/// secret, so it is safe to show and to log.
/// </summary>
public sealed record PrivilegedHostStartOutcome(PrivilegedHostStartStatus Status, string? Reason = null)
{
    public bool Succeeded => Status == PrivilegedHostStartStatus.Started;

    /// <summary>
    /// Interprets an adb-run of the start script. adb's own non-zero exit means the shell never
    /// reached the script; otherwise the script's stdout decides: "info: spawned" is success,
    /// a "fatal:" line is a script failure carrying its reason, and anything else is reported
    /// as an unrecognized outcome rather than being guessed into a false success.
    /// </summary>
    public static PrivilegedHostStartOutcome FromAdbRun(int exitCode, string? stdout, string? stderr)
    {
        var combined = $"{stdout}\n{stderr}";
        if (combined.Contains("info: spawned", StringComparison.Ordinal))
        {
            return new PrivilegedHostStartOutcome(PrivilegedHostStartStatus.Started);
        }

        var fatal = FirstLineWithPrefix(combined, "fatal:");
        if (fatal is not null)
        {
            return new PrivilegedHostStartOutcome(PrivilegedHostStartStatus.ScriptFailed, fatal);
        }

        if (exitCode != 0)
        {
            var reason = FirstNonEmptyLine(stderr) ?? FirstNonEmptyLine(stdout);
            return new PrivilegedHostStartOutcome(PrivilegedHostStartStatus.AdbFailed, reason);
        }

        // Exit 0 but no "spawned" marker: treat as a script failure so the UI never claims a
        // channel it cannot see evidence for.
        var tail = FirstNonEmptyLine(stdout) ?? FirstNonEmptyLine(stderr);
        return new PrivilegedHostStartOutcome(PrivilegedHostStartStatus.ScriptFailed, tail);
    }

    private static string? FirstLineWithPrefix(string text, string prefix)
    {
        foreach (var line in text.Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.StartsWith(prefix, StringComparison.Ordinal))
            {
                return trimmed;
            }
        }

        return null;
    }

    private static string? FirstNonEmptyLine(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        foreach (var line in text.Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.Length > 0)
            {
                return trimmed;
            }
        }

        return null;
    }
}
