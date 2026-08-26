namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>How one <c>adb connect</c> attempt resolved, as read back from adb's own output.</summary>
public enum AdbConnectStatus
{
    /// <summary>adb printed "connected to …" — the transport is up (authorization is probed separately).</summary>
    Connected,

    /// <summary>adb printed "already connected to …" — same happy state, nothing was changed.</summary>
    AlreadyConnected,

    /// <summary>adb printed a refusal ("failed to connect" / "cannot connect"): wrong port, debugging off, or not paired.</summary>
    Refused,

    /// <summary>adb itself failed without a recognizable verdict.</summary>
    AdbFailed,
}

/// <summary>
/// The parsed result of <c>adb connect host:port</c>. Text decides before exit code because
/// several adb builds exit 0 while printing "failed to connect …"; a false success here
/// would strand the user on a card that claims a device it cannot see.
/// </summary>
public sealed record AdbConnectOutcome(AdbConnectStatus Status, string? Detail = null)
{
    public bool Succeeded => Status is AdbConnectStatus.Connected or AdbConnectStatus.AlreadyConnected;

    public static AdbConnectOutcome FromAdbRun(int exitCode, string? stdout, string? stderr)
    {
        var combined = $"{stdout}\n{stderr}";
        foreach (var rawLine in combined.Split('\n'))
        {
            var line = rawLine.Trim();
            if (line.Length == 0)
            {
                continue;
            }

            if (line.StartsWith("already connected to", StringComparison.OrdinalIgnoreCase))
            {
                return new AdbConnectOutcome(AdbConnectStatus.AlreadyConnected, line);
            }

            if (line.StartsWith("connected to", StringComparison.OrdinalIgnoreCase))
            {
                return new AdbConnectOutcome(AdbConnectStatus.Connected, line);
            }

            if (line.StartsWith("failed to connect", StringComparison.OrdinalIgnoreCase)
                || line.StartsWith("cannot connect", StringComparison.OrdinalIgnoreCase)
                || line.StartsWith("unable to connect", StringComparison.OrdinalIgnoreCase))
            {
                return new AdbConnectOutcome(AdbConnectStatus.Refused, line);
            }
        }

        var tail = AdbOutputText.FirstNonEmptyLine(stderr) ?? AdbOutputText.FirstNonEmptyLine(stdout);
        return exitCode != 0
            ? new AdbConnectOutcome(AdbConnectStatus.AdbFailed, tail)
            : new AdbConnectOutcome(AdbConnectStatus.Refused, tail);
    }
}
