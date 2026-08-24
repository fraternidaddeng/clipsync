namespace ClipSync.Peer.Resilience;

/// <summary>
/// System lifecycle signals the sync stack recovers from. The Windows app adapts
/// SystemEvents.PowerModeChanged and NetworkChange.NetworkAddressChanged onto this;
/// tests substitute a fake and raise the events directly.
/// </summary>
public interface ISystemStateEvents
{
    /// <summary>The machine woke from suspend/hibernate; sockets and timers may be stale.</summary>
    event Action? ResumedFromSuspend;

    /// <summary>An interface address appeared or disappeared (Wi-Fi switch, VPN, cable).</summary>
    event Action? NetworkAddressChanged;
}
