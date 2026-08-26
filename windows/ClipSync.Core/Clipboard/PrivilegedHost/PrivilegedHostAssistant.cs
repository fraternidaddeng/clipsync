namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// Orchestrates the PC's optional help with a phone's 特权直读 (privileged read) channel over
/// adb: detect the phone, run the on-device start script, and read back whether the host is
/// alive. It performs no adb call on its own schedule — the caller decides when, and the
/// caller (the view-model) is what enforces the explicit-consent gate before ever invoking
/// this. Nothing here is silent or automatic: every method maps one to one to a user action.
/// </summary>
public sealed class PrivilegedHostAssistant(IAdbRunner runner)
{
    private static readonly IReadOnlyList<string> ListDevicesArguments = new[] { "devices", "-l" };

    /// <summary>Whether an adb executable was located; the card stays in its explain-only state when false.</summary>
    public bool AdbAvailable => runner.IsAvailable;

    /// <summary>Where adb was found, or why not — surfaced as a fact under the card's controls.</summary>
    public string AdbLocationDescription => runner.LocationDescription;

    /// <summary>
    /// Detects attached devices and summarizes the single most-relevant next step. When an
    /// authorized phone is present, also reads back (read-only) whether its privileged host is
    /// already running so the card can distinguish "启动" from "已在运行 / 需重启".
    /// </summary>
    public async Task<PrivilegedHostProbe> ProbeAsync(CancellationToken cancellationToken = default)
    {
        if (!runner.IsAvailable)
        {
            return new PrivilegedHostProbe(
                PrivilegedHostAvailability.AdbUnavailable,
                Array.Empty<AndroidAdbDevice>(),
                Target: null,
                Detail: runner.LocationDescription);
        }

        var list = await runner.RunAsync(ListDevicesArguments, cancellationToken).ConfigureAwait(false);
        if (list.ExitCode != 0)
        {
            return new PrivilegedHostProbe(
                PrivilegedHostAvailability.AdbUnavailable,
                Array.Empty<AndroidAdbDevice>(),
                Target: null,
                Detail: FirstNonEmptyLine(list.StandardError) ?? FirstNonEmptyLine(list.StandardOutput));
        }

        var devices = AdbDeviceListParser.Parse(list.StandardOutput);
        var target = devices.FirstOrDefault(device => device.State == AdbDeviceState.Ready);
        if (target is not null)
        {
            var hostRunning = await CheckHostRunningAsync(target.Serial, cancellationToken).ConfigureAwait(false);
            return new PrivilegedHostProbe(PrivilegedHostAvailability.DeviceReady, devices, target, hostRunning);
        }

        var availability =
            devices.Any(device => device.State == AdbDeviceState.Unauthorized) ? PrivilegedHostAvailability.DeviceUnauthorized
            : devices.Any(device => device.State == AdbDeviceState.Offline) ? PrivilegedHostAvailability.DeviceOffline
            : PrivilegedHostAvailability.NoDevice;
        return new PrivilegedHostProbe(availability, devices, Target: null);
    }

    /// <summary>
    /// Runs the on-device start script for one authorized device and reports how it resolved.
    /// This is the only method that changes state on the phone, and it only ever runs from an
    /// explicit "启动特权直读" tap.
    /// </summary>
    public async Task<PrivilegedHostStartOutcome> StartAsync(string serial, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(serial);
        if (!runner.IsAvailable)
        {
            return new PrivilegedHostStartOutcome(PrivilegedHostStartStatus.AdbFailed);
        }

        var result = await runner.RunAsync(
            WithSerial(serial, PrivilegedHostPaths.StartScriptShellArguments),
            cancellationToken).ConfigureAwait(false);
        return PrivilegedHostStartOutcome.FromAdbRun(result.ExitCode, result.StandardOutput, result.StandardError);
    }

    /// <summary>
    /// Read-only check of whether <c>clipsync_priv_server</c> is running on the given device.
    /// Returns null when the probe itself could not run. Used to tell an already-open channel
    /// apart from one that needs (re)starting after a reboot.
    /// </summary>
    public async Task<bool?> CheckHostRunningAsync(string serial, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(serial);
        if (!runner.IsAvailable)
        {
            return null;
        }

        var result = await runner.RunAsync(
            WithSerial(serial, PrivilegedHostPaths.HostRunningProbeShellArguments),
            cancellationToken).ConfigureAwait(false);
        if (result.ExitCode != 0 && string.IsNullOrWhiteSpace(result.StandardOutput))
        {
            // pgrep exits non-zero with no output when there is simply no match: that is a
            // definite "not running", not a failed probe.
            return result.ExitCode == 1 ? false : (bool?)null;
        }

        return !string.IsNullOrWhiteSpace(result.StandardOutput);
    }

    private static List<string> WithSerial(string serial, IReadOnlyList<string> tail)
    {
        var args = new List<string>(tail.Count + 2) { "-s", serial };
        args.AddRange(tail);
        return args;
    }

    private static string? FirstNonEmptyLine(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        foreach (var line in text.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n'))
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
