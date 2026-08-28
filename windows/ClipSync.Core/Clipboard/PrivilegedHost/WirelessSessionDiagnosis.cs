namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// Where a previously connected wireless-debugging session stands according to the latest
/// <c>adb devices</c> snapshot. Wireless entries live in adb's table under an
/// <c>ip:port</c> serial, so drift (screen-off, network switch, reboot) shows up as the
/// entry going offline or vanishing — never as an explicit "your session died" from adb.
/// </summary>
public enum WirelessSessionState
{
    /// <summary>The endpoint is listed and ready — the wireless session is genuinely alive.</summary>
    Ready,

    /// <summary>The endpoint is still listed but not ready (offline/unauthorized): a stale session.</summary>
    StaleOffline,

    /// <summary>The endpoint is no longer listed at all: adb dropped the dead session.</summary>
    Vanished,
}

/// <summary>
/// Pure classification of wireless adb sessions from parsed device rows, so the card's
/// "wireless connected → later lost" wording and the stale-session recovery are unit-testable
/// without adb. A serial is wireless exactly when it parses as a <c>host:port</c> endpoint —
/// USB serials never contain a valid port suffix in adb's output.
/// </summary>
public static class WirelessSessionDiagnosis
{
    /// <summary>Whether this adb serial denotes a wireless-debugging session (<c>ip:port</c>).</summary>
    public static bool IsWirelessSerial(string? serial) =>
        WirelessAdbEndpoint.TryParse(serial, out _);

    /// <summary>The wireless devices currently listed but not ready — stale sessions worth naming.</summary>
    public static IReadOnlyList<AndroidAdbDevice> StaleWirelessDevices(IReadOnlyList<AndroidAdbDevice> devices)
    {
        ArgumentNullException.ThrowIfNull(devices);
        return devices
            .Where(device => device.State != AdbDeviceState.Ready && IsWirelessSerial(device.Serial))
            .ToList();
    }

    /// <summary>Classifies one known wireless endpoint against the latest device snapshot.</summary>
    public static WirelessSessionState Classify(
        IReadOnlyList<AndroidAdbDevice> devices,
        WirelessAdbEndpoint endpoint)
    {
        ArgumentNullException.ThrowIfNull(devices);
        ArgumentNullException.ThrowIfNull(endpoint);
        var serial = endpoint.ToString();
        var entry = devices.FirstOrDefault(device =>
            string.Equals(device.Serial, serial, StringComparison.OrdinalIgnoreCase));
        return entry switch
        {
            null => WirelessSessionState.Vanished,
            { State: AdbDeviceState.Ready } => WirelessSessionState.Ready,
            _ => WirelessSessionState.StaleOffline,
        };
    }
}

/// <summary>
/// The outcome of a verified wireless connect: the final <c>adb connect</c> verdict, plus
/// whether a stale session (adb claiming "already connected" to a dead transport) had to be
/// disconnected and re-dialed along the way — stated to the user, never silent.
/// </summary>
public sealed record WirelessConnectResult(AdbConnectOutcome Outcome, bool RecoveredStaleSession);
