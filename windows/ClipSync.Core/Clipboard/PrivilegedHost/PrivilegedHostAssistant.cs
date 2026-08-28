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

    // ===== 无线调试（Android 11+）：配对 + 连接协助 =====
    // Same contract as above: every method here runs only from an explicit, consented user
    // action in the caller. The QR flow's discovery polling is bounded and cancellable, and
    // it only ever runs while the user is deliberately showing the pairing QR.

    /// <summary>
    /// Pairs this PC with a phone's wireless-debugging pairing endpoint (<c>adb pair</c>).
    /// The code is the phone's 6-digit pairing code or the QR payload's generated password;
    /// it is passed as a single argv token and never appears in any outcome detail.
    /// </summary>
    public async Task<AdbPairOutcome> PairWirelessAsync(
        WirelessAdbEndpoint endpoint,
        string pairingCode,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        ArgumentException.ThrowIfNullOrWhiteSpace(pairingCode);
        if (!runner.IsAvailable)
        {
            return new AdbPairOutcome(AdbPairStatus.AdbFailed, runner.LocationDescription);
        }

        var result = await runner.RunAsync(
            WirelessAdbCommands.Pair(endpoint, pairingCode),
            cancellationToken).ConfigureAwait(false);
        return AdbPairOutcome.FromAdbRun(result.ExitCode, result.StandardOutput, result.StandardError);
    }

    /// <summary>Connects to a paired phone's wireless-debugging endpoint (<c>adb connect</c>).</summary>
    public async Task<AdbConnectOutcome> ConnectWirelessAsync(
        WirelessAdbEndpoint endpoint,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        if (!runner.IsAvailable)
        {
            return new AdbConnectOutcome(AdbConnectStatus.AdbFailed, runner.LocationDescription);
        }

        var result = await runner.RunAsync(WirelessAdbCommands.Connect(endpoint), cancellationToken).ConfigureAwait(false);
        return AdbConnectOutcome.FromAdbRun(result.ExitCode, result.StandardOutput, result.StandardError);
    }

    /// <summary>
    /// Drops one wireless session from adb's table (<c>adb disconnect host:port</c>). Failure is
    /// tolerated by callers — "no such device" simply means there was nothing stale to clear.
    /// </summary>
    public async Task DisconnectWirelessAsync(
        WirelessAdbEndpoint endpoint,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        if (!runner.IsAvailable)
        {
            return;
        }

        await runner.RunAsync(WirelessAdbCommands.Disconnect(endpoint), cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// <c>adb connect</c> with a stale-session check: adb answers "already connected" from its
    /// session table even when the underlying transport died (wireless port drift after
    /// screen-off, network switch, or reboot). When that answer is not backed by a ready device
    /// in <c>adb devices</c>, the stale entry is disconnected and dialed once more for real, so
    /// the card can never claim a connection the device list contradicts.
    /// </summary>
    public async Task<WirelessConnectResult> ConnectWirelessVerifiedAsync(
        WirelessAdbEndpoint endpoint,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        var outcome = await ConnectWirelessAsync(endpoint, cancellationToken).ConfigureAwait(false);
        if (outcome.Status != AdbConnectStatus.AlreadyConnected)
        {
            return new WirelessConnectResult(outcome, RecoveredStaleSession: false);
        }

        var probe = await ProbeAsync(cancellationToken).ConfigureAwait(false);
        if (WirelessSessionDiagnosis.Classify(probe.Devices, endpoint) == WirelessSessionState.Ready)
        {
            return new WirelessConnectResult(outcome, RecoveredStaleSession: false);
        }

        await DisconnectWirelessAsync(endpoint, cancellationToken).ConfigureAwait(false);
        var redialed = await ConnectWirelessAsync(endpoint, cancellationToken).ConfigureAwait(false);
        return new WirelessConnectResult(redialed, RecoveredStaleSession: true);
    }

    /// <summary>
    /// Read-only: whether this adb's mDNS discovery stack is usable (<c>adb mdns check</c>).
    /// False steers the card to the manual pairing-code path instead of showing a QR whose
    /// hands-free discovery could never fire.
    /// </summary>
    public async Task<bool> CheckMdnsSupportAsync(CancellationToken cancellationToken = default)
    {
        if (!runner.IsAvailable)
        {
            return false;
        }

        var result = await runner.RunAsync(WirelessAdbCommands.CheckMdns, cancellationToken).ConfigureAwait(false);
        var combined = $"{result.StandardOutput}\n{result.StandardError}";
        return result.ExitCode == 0
            && !combined.Contains("error", StringComparison.OrdinalIgnoreCase)
            && !combined.Contains("disabled", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Read-only listing of what adb's mDNS sees right now; empty when the listing itself failed.</summary>
    public async Task<IReadOnlyList<AdbMdnsServiceRow>> ListMdnsServicesAsync(CancellationToken cancellationToken = default)
    {
        if (!runner.IsAvailable)
        {
            return Array.Empty<AdbMdnsServiceRow>();
        }

        var result = await runner.RunAsync(WirelessAdbCommands.ListMdnsServices, cancellationToken).ConfigureAwait(false);
        return result.ExitCode != 0
            ? Array.Empty<AdbMdnsServiceRow>()
            : AdbMdnsServicesParser.Parse(result.StandardOutput);
    }

    /// <summary>
    /// One discovery pass for the QR flow: the pairing endpoint a phone announced after
    /// scanning our QR, matched by the QR's exact service name so another tool's pairing
    /// session can never be grabbed. Null when the phone has not scanned yet.
    /// </summary>
    public async Task<WirelessAdbEndpoint?> DiscoverPairingEndpointAsync(
        string serviceName,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(serviceName);
        var rows = await ListMdnsServicesAsync(cancellationToken).ConfigureAwait(false);
        return rows.FirstOrDefault(row =>
                row.IsOfType(AdbMdnsServicesParser.PairingServiceType)
                && string.Equals(row.InstanceName, serviceName, StringComparison.Ordinal))
            ?.Endpoint;
    }

    /// <summary>
    /// The connect endpoint of a wireless-debugging phone, from mDNS. When a preferred host
    /// is given (the host we just paired with), its announcement wins; otherwise the first
    /// one seen. Null when nothing is announced — the user then types the phone's own
    /// "IP 地址和端口" line instead.
    /// </summary>
    public async Task<WirelessAdbEndpoint?> DiscoverConnectEndpointAsync(
        string? preferredHost = null,
        CancellationToken cancellationToken = default)
    {
        var rows = await ListMdnsServicesAsync(cancellationToken).ConfigureAwait(false);
        var candidates = rows
            .Where(row => row.IsOfType(AdbMdnsServicesParser.ConnectServiceType) && row.Endpoint is not null)
            .Select(row => row.Endpoint!)
            .ToList();
        if (candidates.Count == 0)
        {
            return null;
        }

        return preferredHost is null
            ? candidates[0]
            : candidates.FirstOrDefault(endpoint =>
                string.Equals(endpoint.Host, preferredHost, StringComparison.OrdinalIgnoreCase))
                ?? candidates[0];
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
