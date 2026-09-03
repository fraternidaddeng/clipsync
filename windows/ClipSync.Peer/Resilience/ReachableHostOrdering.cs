using System.Net;
using System.Net.NetworkInformation;

namespace ClipSync.Peer.Resilience;

/// <summary>
/// One bind candidate with the interface facts the ordering needs. Addresses the user typed
/// into 额外监听地址 carry <see cref="UserConfigured"/> and no interface facts.
/// </summary>
public sealed record CandidateAddress(
    IPAddress Address,
    string InterfaceName,
    string InterfaceDescription,
    NetworkInterfaceType InterfaceType,
    bool HasIpv4Gateway,
    bool IsUp,
    bool UserConfigured = false)
{
    public static CandidateAddress User(IPAddress address) =>
        new(address, string.Empty, string.Empty, NetworkInterfaceType.Unknown, HasIpv4Gateway: false, IsUp: true, UserConfigured: true);
}

/// <summary>
/// Pure ordering of the QR host candidates. The phone dials the hosts in QR order with a
/// six-second timeout each, so every dead address in front of the real Wi-Fi/Ethernet one
/// costs the user six seconds. WSL/Hyper-V switches, VM host-only adapters, and VPN tunnels
/// all present private IPv4 addresses that the phone cannot reach; they go last. Tiers:
/// <list type="number">
/// <item>Up Wi-Fi/Ethernet with an IPv4 default gateway — the LAN the phone is on.</item>
/// <item>User-configured extra addresses — explicit intent beats guessing, but must not
/// push the real LAN address back.</item>
/// <item>Any other interface with an IPv4 gateway.</item>
/// <item>Physical-looking interfaces without a gateway.</item>
/// <item>Interfaces whose name or description matches a virtual-adapter marker, gateway
/// holders first (a Hyper-V external switch really does carry the LAN address).</item>
/// </list>
/// Within a tier the input order (interface enumeration order) is kept.
/// </summary>
public static class ReachableHostOrdering
{
    /// <summary>Case-insensitive substrings of adapter names/descriptions that mark virtual, VM, or tunnel adapters.</summary>
    internal static readonly string[] VirtualAdapterMarkers =
    [
        "vEthernet",
        "Hyper-V",
        "WSL",
        "VMware",
        "VirtualBox",
        "Host-Only",
        "TAP",
        "Wintun",
        "WireGuard",
        "Tailscale",
        "ZeroTier",
        "Docker",
        "Npcap",
        "Loopback",
    ];

    private const int TierLanWithGateway = 0;
    private const int TierUserConfigured = 1;
    private const int TierOtherWithGateway = 2;
    private const int TierPhysicalWithoutGateway = 3;
    private const int TierVirtualWithGateway = 4;
    private const int TierVirtualWithoutGateway = 5;

    /// <summary>Stable tier sort; the address set is unchanged, only its order.</summary>
    public static IReadOnlyList<CandidateAddress> Order(IReadOnlyList<CandidateAddress> candidates)
    {
        ArgumentNullException.ThrowIfNull(candidates);
        // Enumerable.OrderBy is a stable sort, so equal tiers keep enumeration order.
        return candidates.OrderBy(Tier).ToList();
    }

    internal static int Tier(CandidateAddress candidate)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        if (candidate.UserConfigured)
        {
            return TierUserConfigured;
        }

        if (LooksVirtual(candidate))
        {
            return candidate.HasIpv4Gateway ? TierVirtualWithGateway : TierVirtualWithoutGateway;
        }

        if (!candidate.HasIpv4Gateway)
        {
            return TierPhysicalWithoutGateway;
        }

        var lanType = candidate.InterfaceType is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet;
        return lanType && candidate.IsUp ? TierLanWithGateway : TierOtherWithGateway;
    }

    internal static bool LooksVirtual(CandidateAddress candidate)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        foreach (var marker in VirtualAdapterMarkers)
        {
            if (candidate.InterfaceName.Contains(marker, StringComparison.OrdinalIgnoreCase)
                || candidate.InterfaceDescription.Contains(marker, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }

        return false;
    }
}
