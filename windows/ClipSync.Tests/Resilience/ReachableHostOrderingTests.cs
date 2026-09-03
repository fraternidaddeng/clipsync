using System.Net;
using System.Net.NetworkInformation;
using ClipSync.Peer.Resilience;

namespace ClipSync.Tests.Resilience;

public sealed class ReachableHostOrderingTests
{
    private static CandidateAddress Adapter(
        string address,
        string name,
        string description,
        NetworkInterfaceType type,
        bool gateway,
        bool up = true) =>
        new(IPAddress.Parse(address), name, description, type, gateway, up);

    private static readonly CandidateAddress WslSwitch = Adapter(
        "172.29.128.1", "vEthernet (WSL (Hyper-V firewall))", "Hyper-V Virtual Ethernet Adapter", NetworkInterfaceType.Ethernet, gateway: false);

    private static readonly CandidateAddress VmwareHostOnly = Adapter(
        "192.168.56.1", "VMware Network Adapter VMnet1", "VMware Virtual Ethernet Adapter for VMnet1", NetworkInterfaceType.Ethernet, gateway: false);

    private static readonly CandidateAddress WireGuard = Adapter(
        "10.8.0.2", "wg0", "WireGuard Tunnel", NetworkInterfaceType.Unknown, gateway: false);

    private static readonly CandidateAddress WiFi = Adapter(
        "192.168.1.23", "Wi-Fi", "Intel(R) Wi-Fi 6 AX201 160MHz", NetworkInterfaceType.Wireless80211, gateway: true);

    private static readonly CandidateAddress Ethernet = Adapter(
        "192.168.1.40", "Ethernet", "Realtek PCIe GbE Family Controller", NetworkInterfaceType.Ethernet, gateway: true);

    private static readonly CandidateAddress UsbTetherNoGateway = Adapter(
        "192.168.42.10", "Ethernet 3", "Remote NDIS based Internet Sharing Device", NetworkInterfaceType.Ethernet, gateway: false);

    private static readonly CandidateAddress PppWithGateway = Adapter(
        "10.20.0.7", "Corp VPN", "WAN Miniport (PPTP)", NetworkInterfaceType.Ppp, gateway: true);

    private static string[] Addresses(IReadOnlyList<CandidateAddress> ordered) =>
        ordered.Select(candidate => candidate.Address.ToString()).ToArray();

    [Fact]
    public void VirtualAdaptersEnumeratedFirstAreMovedToTheEnd()
    {
        // The realistic developer-box enumeration order: WSL, VMware, VPN, and only then Wi-Fi.
        var ordered = ReachableHostOrdering.Order([WslSwitch, VmwareHostOnly, WireGuard, WiFi]);

        Assert.Equal(
            [WiFi.Address.ToString(), WslSwitch.Address.ToString(), VmwareHostOnly.Address.ToString(), WireGuard.Address.ToString()],
            Addresses(ordered));
    }

    [Fact]
    public void WiFiWithGatewayComesFirstAheadOfEveryOtherTier()
    {
        var ordered = ReachableHostOrdering.Order([UsbTetherNoGateway, PppWithGateway, WslSwitch, WiFi]);

        Assert.Equal(WiFi, ordered[0]);
        Assert.Equal(
            [WiFi.Address.ToString(), PppWithGateway.Address.ToString(), UsbTetherNoGateway.Address.ToString(), WslSwitch.Address.ToString()],
            Addresses(ordered));
    }

    [Fact]
    public void UserConfiguredAddressesSitBetweenTheLanTierAndTheRest()
    {
        var tailscaleTyped = CandidateAddress.User(IPAddress.Parse("100.100.1.7"));

        var ordered = ReachableHostOrdering.Order([WslSwitch, tailscaleTyped, PppWithGateway, WiFi, Ethernet]);

        // Explicit intent beats guessing, but never displaces the real LAN addresses.
        Assert.Equal(
            [
                WiFi.Address.ToString(),
                Ethernet.Address.ToString(),
                tailscaleTyped.Address.ToString(),
                PppWithGateway.Address.ToString(),
                WslSwitch.Address.ToString(),
            ],
            Addresses(ordered));
    }

    [Fact]
    public void OrderingIsStableWithinATier()
    {
        var wifiB = Adapter("192.168.1.24", "Wi-Fi 2", "Second radio", NetworkInterfaceType.Wireless80211, gateway: true);
        var virtualB = Adapter("172.30.0.1", "vEthernet (Default Switch)", "Hyper-V Virtual Ethernet Adapter #2", NetworkInterfaceType.Ethernet, gateway: false);

        var ordered = ReachableHostOrdering.Order([WslSwitch, Ethernet, virtualB, WiFi, wifiB]);

        Assert.Equal(
            [
                Ethernet.Address.ToString(),
                WiFi.Address.ToString(),
                wifiB.Address.ToString(),
                WslSwitch.Address.ToString(),
                virtualB.Address.ToString(),
            ],
            Addresses(ordered));
    }

    [Fact]
    public void VirtualAdapterMatchingIsCaseInsensitiveOnNameOrDescription()
    {
        Assert.True(ReachableHostOrdering.LooksVirtual(Adapter("10.0.0.1", "ethernet 5", "TAILSCALE tunnel", NetworkInterfaceType.Unknown, gateway: false)));
        Assert.True(ReachableHostOrdering.LooksVirtual(Adapter("10.0.0.1", "zerotier one", "some driver", NetworkInterfaceType.Unknown, gateway: false)));
        Assert.True(ReachableHostOrdering.LooksVirtual(Adapter("10.0.0.1", "Ethernet 2", "Npcap Loopback Adapter", NetworkInterfaceType.Ethernet, gateway: false)));
        Assert.False(ReachableHostOrdering.LooksVirtual(WiFi));
        Assert.False(ReachableHostOrdering.LooksVirtual(Ethernet));
    }

    [Fact]
    public void VirtualAdapterWithAGatewayStillTrailsPhysicalOnesButLeadsOtherVirtualOnes()
    {
        // Hyper-V external switch: the LAN address lives on a vEthernet adapter with a gateway.
        var externalSwitch = Adapter("192.168.1.50", "vEthernet (External)", "Hyper-V Virtual Ethernet Adapter #3", NetworkInterfaceType.Ethernet, gateway: true);

        var ordered = ReachableHostOrdering.Order([WslSwitch, externalSwitch, UsbTetherNoGateway]);

        Assert.Equal(
            [UsbTetherNoGateway.Address.ToString(), externalSwitch.Address.ToString(), WslSwitch.Address.ToString()],
            Addresses(ordered));
    }

    [Fact]
    public void DownedLanAdapterDoesNotClaimTheFirstTier()
    {
        var downedWifi = Adapter("192.168.1.99", "Wi-Fi", "Intel(R) Wi-Fi", NetworkInterfaceType.Wireless80211, gateway: true, up: false);

        Assert.Equal(ReachableHostOrdering.Tier(PppWithGateway), ReachableHostOrdering.Tier(downedWifi));
        Assert.True(ReachableHostOrdering.Tier(downedWifi) > ReachableHostOrdering.Tier(WiFi));
    }

    [Fact]
    public void PlannerStillCapsTheOrderedListAtEight()
    {
        var candidates = new List<CandidateAddress> { WslSwitch };
        candidates.AddRange(Enumerable.Range(1, 10).Select(i =>
            Adapter($"192.168.1.{i}", $"Ethernet {i}", "Realtek", NetworkInterfaceType.Ethernet, gateway: true)));

        var resolved = new List<IPAddress> { IPAddress.Loopback };
        resolved.AddRange(ReachableHostOrdering.Order(candidates).Select(candidate => candidate.Address));
        var plan = NetworkRefreshPlanner.Plan(new NetworkRefreshContext
        {
            BoundAddresses = [IPAddress.Loopback],
            ResolvedAddresses = resolved,
            CurrentReachableHosts = [],
            AfterResume = true,
            ServerListening = true
        });

        Assert.Equal(NetworkRefreshPlanner.MaxReachableHosts, plan.ReachableHosts.Count);
        // The virtual adapter that was enumerated first is exactly what the cap cuts off.
        Assert.DoesNotContain(WslSwitch.Address.ToString(), plan.ReachableHosts);
        Assert.Equal("192.168.1.1", plan.ReachableHosts[0]);
    }
}
