using System.Net;

namespace ClipSync.Peer.Resilience;

/// <summary>Inputs for one refresh decision; all state is captured by the caller.</summary>
public sealed record NetworkRefreshContext
{
    /// <summary>Addresses the peer server is currently bound to; empty when it is down.</summary>
    public required IReadOnlyList<IPAddress> BoundAddresses { get; init; }

    /// <summary>Freshly resolved bind candidates (loopback + private LAN + user extras).</summary>
    public required IReadOnlyList<IPAddress> ResolvedAddresses { get; init; }

    /// <summary>Hosts currently advertised to scanning phones (QR payload).</summary>
    public required IReadOnlyList<string> CurrentReachableHosts { get; init; }

    /// <summary>True when the machine just woke from suspend (existing sessions are dead).</summary>
    public required bool AfterResume { get; init; }

    /// <summary>True while the peer server holds a live listener.</summary>
    public required bool ServerListening { get; init; }
}

public sealed record NetworkRefreshPlan
{
    /// <summary>Rebind the peer server to <see cref="ReachableHosts"/>' address set.</summary>
    public required bool RestartServer { get; init; }

    /// <summary>Recreate the UDP discovery broadcaster (fresh socket / new port).</summary>
    public required bool RestartBroadcaster { get; init; }

    /// <summary>The advertised host list differs from what the QR payload showed before.</summary>
    public required bool HostsChanged { get; init; }

    /// <summary>Non-loopback hosts a phone can actually reach after this plan executes.</summary>
    public required IReadOnlyList<string> ReachableHosts { get; init; }
}

/// <summary>
/// Pure decision logic for sleep/wake and network-change recovery, kept free of sockets so
/// ClipSync.Tests can cover every branch. The host executes the plan's side effects.
/// </summary>
public static class NetworkRefreshPlanner
{
    /// <summary>The QR pairing payload carries at most eight host candidates.</summary>
    public const int MaxReachableHosts = 8;

    public static NetworkRefreshPlan Plan(NetworkRefreshContext context)
    {
        ArgumentNullException.ThrowIfNull(context);

        var resolved = context.ResolvedAddresses;
        var resolvedNonLoopback = resolved.Where(address => !IPAddress.IsLoopback(address)).ToList();
        var boundNonLoopback = context.BoundAddresses.Where(address => !IPAddress.IsLoopback(address)).ToList();
        var addressSetChanged = !SameSet(context.BoundAddresses, resolved);
        var anyBindingStillValid = boundNonLoopback.Any(resolved.Contains);

        bool restartServer;
        if (!context.ServerListening)
        {
            // The listener is down (earlier rebind failed or never started): always try to
            // bring it back, even on loopback only, so the endpoint reports online again.
            restartServer = true;
        }
        else if (!addressSetChanged)
        {
            restartServer = false;
        }
        else if (context.AfterResume)
        {
            // After suspend every TCP session is dead anyway, so rebinding to the fresh
            // address set costs nothing and picks up the post-wake network immediately.
            restartServer = true;
        }
        else
        {
            // A live network change must not kill sessions on still-valid bindings. Rebind
            // only when every non-loopback binding is gone and a usable address appeared.
            restartServer = !anyBindingStillValid && resolvedNonLoopback.Count > 0;
        }

        // Without a rebind only the still-bound addresses are reachable; stale bindings
        // are dropped from the QR payload, and brand-new interfaces wait for a rebind.
        var hostSource = restartServer
            ? resolvedNonLoopback
            : boundNonLoopback.Where(resolved.Contains);
        var reachableHosts = hostSource
            .Select(address => address.ToString())
            .Take(MaxReachableHosts)
            .ToList();
        var hostsChanged = !reachableHosts.SequenceEqual(context.CurrentReachableHosts, StringComparer.Ordinal);

        return new NetworkRefreshPlan
        {
            RestartServer = restartServer,
            // After resume the UDP socket may sit on a stale interface; after a rebind the
            // advertised port may have moved. Either way the broadcaster needs a fresh start.
            RestartBroadcaster = context.AfterResume || restartServer || hostsChanged,
            HostsChanged = hostsChanged,
            ReachableHosts = reachableHosts
        };
    }

    private static bool SameSet(IReadOnlyList<IPAddress> left, IReadOnlyList<IPAddress> right) =>
        left.Count == right.Count && left.All(right.Contains) && right.All(left.Contains);
}
