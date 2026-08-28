namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// The exact adb argument vectors for the wireless-debugging flow, mirroring
/// <see cref="PrivilegedHostPaths"/>: discrete tokens (no shell, no quoting ambiguity),
/// asserted verbatim by unit tests so a wording drift can never silently change what runs.
/// </summary>
public static class WirelessAdbCommands
{
    /// <summary><c>adb pair host:port code</c> — the code/password travels as one argv token, never a shell string.</summary>
    public static IReadOnlyList<string> Pair(WirelessAdbEndpoint endpoint, string pairingCode)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        ArgumentException.ThrowIfNullOrWhiteSpace(pairingCode);
        return new[] { "pair", endpoint.ToString(), pairingCode };
    }

    /// <summary><c>adb connect host:port</c>.</summary>
    public static IReadOnlyList<string> Connect(WirelessAdbEndpoint endpoint)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        return new[] { "connect", endpoint.ToString() };
    }

    /// <summary>
    /// <c>adb disconnect host:port</c> — drops one wireless session from adb's table. Used to
    /// clear a stale entry (screen-off / network-switch port drift leaves adb claiming
    /// "already connected" to a dead transport) before dialing again.
    /// </summary>
    public static IReadOnlyList<string> Disconnect(WirelessAdbEndpoint endpoint)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        return new[] { "disconnect", endpoint.ToString() };
    }

    /// <summary><c>adb mdns services</c> — read-only discovery listing, parsed by <see cref="AdbMdnsServicesParser"/>.</summary>
    public static IReadOnlyList<string> ListMdnsServices { get; } = new[] { "mdns", "services" };

    /// <summary><c>adb mdns check</c> — read-only: whether this adb's mDNS discovery stack is usable at all.</summary>
    public static IReadOnlyList<string> CheckMdns { get; } = new[] { "mdns", "check" };
}
