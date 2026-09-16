namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// Where a previously connected wireless-debugging session stands according to the latest
/// <c>adb devices</c> snapshot. Wireless entries live in adb's table under an
/// <c>ip:port</c> serial or, on current platform-tools, an mDNS service-name serial, so
/// drift (screen-off, network switch, reboot) shows up as the entry going offline or
/// vanishing — never as an explicit "your session died" from adb.
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
/// without adb. A serial is wireless when it parses as a <c>host:port</c> endpoint <em>or</em>
/// when it is adb 37+'s mDNS service name (<c>adb-…._adb-tls-connect._tcp</c>). USB serials
/// match neither form.
/// </summary>
public static class WirelessSessionDiagnosis
{
    /// <summary>The connect-side mDNS type adb 37+ uses as the device serial after <c>adb connect</c>.</summary>
    public const string MdnsConnectSerialSuffix = "._adb-tls-connect._tcp";

    /// <summary>The pairing-side mDNS type; rarely listed in <c>adb devices</c>, still not a USB serial.</summary>
    public const string MdnsPairingSerialSuffix = "._adb-tls-pairing._tcp";

    /// <summary>
    /// Whether this adb serial denotes a wireless-debugging session: a <c>host:port</c>
    /// endpoint, or an mDNS service-name serial from current platform-tools.
    /// </summary>
    public static bool IsWirelessSerial(string? serial) =>
        WirelessAdbEndpoint.TryParse(serial, out _) || IsMdnsWirelessSerial(serial);

    /// <summary>Whether this serial is adb's mDNS connect-service name (not a pairing announcement).</summary>
    public static bool IsMdnsConnectSerial(string? serial) =>
        !string.IsNullOrEmpty(serial)
        && serial.Contains(MdnsConnectSerialSuffix, StringComparison.OrdinalIgnoreCase);

    /// <summary>The wireless devices currently listed but not ready — stale sessions worth naming.</summary>
    public static IReadOnlyList<AndroidAdbDevice> StaleWirelessDevices(IReadOnlyList<AndroidAdbDevice> devices)
    {
        ArgumentNullException.ThrowIfNull(devices);
        return devices
            .Where(device => device.State != AdbDeviceState.Ready && IsWirelessSerial(device.Serial))
            .ToList();
    }

    /// <summary>
    /// Classifies one known wireless endpoint against the latest device snapshot.
    /// Exact <c>host:port</c> wins; when that row is absent, a ready/offline
    /// <c>adb-…._adb-tls-connect._tcp</c> listing is the same session (adb 37+
    /// stopped echoing the IP:port we dialed).
    /// </summary>
    public static WirelessSessionState Classify(
        IReadOnlyList<AndroidAdbDevice> devices,
        WirelessAdbEndpoint endpoint)
    {
        ArgumentNullException.ThrowIfNull(devices);
        ArgumentNullException.ThrowIfNull(endpoint);
        var serial = endpoint.ToString();
        var entry = devices.FirstOrDefault(device =>
            string.Equals(device.Serial, serial, StringComparison.OrdinalIgnoreCase));
        if (entry is not null)
        {
            return entry.State == AdbDeviceState.Ready
                ? WirelessSessionState.Ready
                : WirelessSessionState.StaleOffline;
        }

        var mdnsConnect = devices.Where(device => IsMdnsConnectSerial(device.Serial)).ToList();
        if (mdnsConnect.Count == 0)
        {
            return WirelessSessionState.Vanished;
        }

        return mdnsConnect.Any(device => device.State == AdbDeviceState.Ready)
            ? WirelessSessionState.Ready
            : WirelessSessionState.StaleOffline;
    }

    private static bool IsMdnsWirelessSerial(string? serial) =>
        !string.IsNullOrEmpty(serial)
        && (serial.Contains(MdnsConnectSerialSuffix, StringComparison.OrdinalIgnoreCase)
            || serial.Contains(MdnsPairingSerialSuffix, StringComparison.OrdinalIgnoreCase));
}

/// <summary>
/// The outcome of a verified wireless connect: the final <c>adb connect</c> verdict, plus
/// whether a stale session (adb claiming "already connected" to a dead transport) had to be
/// disconnected and re-dialed along the way — stated to the user, never silent.
/// </summary>
public sealed record WirelessConnectResult(AdbConnectOutcome Outcome, bool RecoveredStaleSession);
