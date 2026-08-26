namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>How one <c>adb pair</c> attempt resolved, as read back from adb's own output.</summary>
public enum AdbPairStatus
{
    /// <summary>adb printed "Successfully paired to …" — the phone accepted the code/QR secret.</summary>
    Paired,

    /// <summary>adb reached the phone but pairing was refused: wrong/expired code, or the phone closed its pairing dialog.</summary>
    Rejected,

    /// <summary>adb itself failed before a pairing verdict (unreachable endpoint, transport error).</summary>
    AdbFailed,
}

/// <summary>
/// The parsed result of <c>adb pair host:port code</c>. The detail is adb's own short line —
/// never the pairing code or QR password, so it is safe to show and to log.
/// </summary>
public sealed record AdbPairOutcome(AdbPairStatus Status, string? Detail = null)
{
    public bool Succeeded => Status == AdbPairStatus.Paired;

    /// <summary>
    /// Interprets an adb-pair run. adb prints its verdict to stdout; the exit code is
    /// secondary (some builds exit 0 while printing "Failed: …"), so the text decides first:
    /// "Successfully paired" is success, a "Failed:" line is a rejection carrying adb's
    /// reason, and anything else without a success marker is reported honestly as unresolved
    /// rather than guessed into a false success.
    /// </summary>
    public static AdbPairOutcome FromAdbRun(int exitCode, string? stdout, string? stderr)
    {
        var combined = $"{stdout}\n{stderr}";
        var success = FirstLineContaining(combined, "successfully paired");
        if (success is not null)
        {
            return new AdbPairOutcome(AdbPairStatus.Paired, success);
        }

        var failed = FirstLineContaining(combined, "failed:");
        if (failed is not null)
        {
            return new AdbPairOutcome(AdbPairStatus.Rejected, failed);
        }

        var tail = AdbOutputText.FirstNonEmptyLine(stderr) ?? AdbOutputText.FirstNonEmptyLine(stdout);
        return exitCode != 0
            ? new AdbPairOutcome(AdbPairStatus.AdbFailed, tail)
            : new AdbPairOutcome(AdbPairStatus.Rejected, tail);
    }

    private static string? FirstLineContaining(string text, string marker)
    {
        foreach (var line in text.Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.Contains(marker, StringComparison.OrdinalIgnoreCase))
            {
                return trimmed;
            }
        }

        return null;
    }
}
