using System.Net;
using ClipSync.Peer.Resilience;

namespace ClipSync.Tests.Resilience;

public sealed class NetworkRefreshPlannerTests
{
    private static readonly IPAddress Loopback = IPAddress.Loopback;
    private static readonly IPAddress LanA = IPAddress.Parse("192.168.1.20");
    private static readonly IPAddress LanB = IPAddress.Parse("10.0.0.5");
    private static readonly IPAddress Tailscale = IPAddress.Parse("100.100.1.7");

    private static NetworkRefreshContext Context(
        IReadOnlyList<IPAddress> bound,
        IReadOnlyList<IPAddress> resolved,
        IReadOnlyList<string>? currentHosts = null,
        bool afterResume = false,
        bool serverListening = true) => new()
    {
        BoundAddresses = bound,
        ResolvedAddresses = resolved,
        CurrentReachableHosts = currentHosts ?? bound
            .Where(address => !IPAddress.IsLoopback(address))
            .Select(address => address.ToString())
            .ToList(),
        AfterResume = afterResume,
        ServerListening = serverListening
    };

    [Fact]
    public void ResumeWithUnchangedAddressesKeepsServerButRestartsBroadcaster()
    {
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback, LanA],
            resolved: [Loopback, LanA],
            afterResume: true));

        Assert.False(plan.RestartServer);
        Assert.True(plan.RestartBroadcaster);
        Assert.False(plan.HostsChanged);
        Assert.Equal([LanA.ToString()], plan.ReachableHosts);
    }

    [Fact]
    public void ResumeWithNewAddressSetRebindsServerAndAdvertisesFreshHosts()
    {
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback, LanA],
            resolved: [Loopback, LanB],
            afterResume: true));

        Assert.True(plan.RestartServer);
        Assert.True(plan.RestartBroadcaster);
        Assert.True(plan.HostsChanged);
        Assert.Equal([LanB.ToString()], plan.ReachableHosts);
    }

    [Fact]
    public void NetworkChangeWithLiveBindingNeverKillsTheServer()
    {
        // A new interface appeared but the existing binding is still valid: sessions on it
        // must survive, so no rebind; the new address stays out of the QR payload for now.
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback, LanA],
            resolved: [Loopback, LanA, Tailscale]));

        Assert.False(plan.RestartServer);
        Assert.False(plan.HostsChanged);
        Assert.Equal([LanA.ToString()], plan.ReachableHosts);
    }

    [Fact]
    public void NetworkChangeDropsStaleBindingFromReachableHosts()
    {
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback, LanA, LanB],
            resolved: [Loopback, LanA]));

        Assert.False(plan.RestartServer);
        Assert.True(plan.HostsChanged);
        Assert.True(plan.RestartBroadcaster);
        Assert.Equal([LanA.ToString()], plan.ReachableHosts);
    }

    [Fact]
    public void NetworkChangeRebindsWhenEveryBindingIsGoneAndANewAddressExists()
    {
        // Wi-Fi switched networks: the old binding is dead (no sessions can survive on it),
        // and only a rebind can pick up the new address.
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback, LanA],
            resolved: [Loopback, LanB]));

        Assert.True(plan.RestartServer);
        Assert.True(plan.RestartBroadcaster);
        Assert.True(plan.HostsChanged);
        Assert.Equal([LanB.ToString()], plan.ReachableHosts);
    }

    [Fact]
    public void NetworkChangeToLoopbackOnlyKeepsServerAndEmptiesHosts()
    {
        // All interfaces went down (airplane mode): nothing to rebind to yet, so keep the
        // listener and advertise no hosts until an address comes back.
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback, LanA],
            resolved: [Loopback]));

        Assert.False(plan.RestartServer);
        Assert.True(plan.HostsChanged);
        Assert.Empty(plan.ReachableHosts);
    }

    [Fact]
    public void DownedServerIsRestartedEvenOnLoopbackOnly()
    {
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [],
            resolved: [Loopback],
            currentHosts: [],
            serverListening: false));

        Assert.True(plan.RestartServer);
        Assert.True(plan.RestartBroadcaster);
        Assert.Empty(plan.ReachableHosts);
    }

    [Fact]
    public void QuietNetworkChangeProducesNoOpPlan()
    {
        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback, LanA],
            resolved: [Loopback, LanA]));

        Assert.False(plan.RestartServer);
        Assert.False(plan.RestartBroadcaster);
        Assert.False(plan.HostsChanged);
    }

    [Fact]
    public void ReachableHostsAreCappedAtEightForTheQrPayload()
    {
        var resolved = new List<IPAddress> { Loopback };
        resolved.AddRange(Enumerable.Range(1, 12).Select(i => IPAddress.Parse($"192.168.1.{i}")));

        var plan = NetworkRefreshPlanner.Plan(Context(
            bound: [Loopback],
            resolved: resolved,
            currentHosts: [],
            afterResume: true));

        Assert.Equal(NetworkRefreshPlanner.MaxReachableHosts, plan.ReachableHosts.Count);
    }
}
