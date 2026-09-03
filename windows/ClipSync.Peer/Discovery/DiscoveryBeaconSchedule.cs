namespace ClipSync.Peer.Discovery;

/// <summary>
/// Pure cadence state for the discovery beacon: a slow idle rhythm, and a dense "pairing
/// beacon" rhythm while at least one QR window is open. Holds are reference-counted so
/// overlapping windows (or a window re-issuing tickets) never flip the cadence early. The
/// host owns the timer and the socket; this type only answers "which interval now" and
/// "did this call change the cadence".
/// </summary>
public sealed class DiscoveryBeaconSchedule
{
    /// <summary>Background rhythm: enough for the phone's device list, cheap on the LAN.</summary>
    public static readonly TimeSpan IdleInterval = TimeSpan.FromMinutes(5);

    /// <summary>
    /// Pairing rhythm: the phone's pairing page listens for this PC's beacon as a firewall
    /// diagnostic and to move the sender address to the front of its dial list, so a scan
    /// must not wait minutes for evidence.
    /// </summary>
    public static readonly TimeSpan PairingInterval = TimeSpan.FromSeconds(2);

    private readonly object gate = new();
    private int pairingHolds;

    /// <summary>True while at least one pairing hold is outstanding.</summary>
    public bool PairingActive
    {
        get
        {
            lock (gate)
            {
                return pairingHolds > 0;
            }
        }
    }

    /// <summary>The interval the beacon timer must run at right now.</summary>
    public TimeSpan CurrentInterval => PairingActive ? PairingInterval : IdleInterval;

    /// <summary>
    /// Takes one pairing hold. Returns true only for the hold that switched the cadence from
    /// idle to pairing — the caller then broadcasts immediately and re-arms the timer.
    /// </summary>
    public bool BeginPairing()
    {
        lock (gate)
        {
            pairingHolds++;
            return pairingHolds == 1;
        }
    }

    /// <summary>
    /// Releases one pairing hold. Returns true only when the last hold went away and the
    /// cadence is back to idle; releasing with no hold outstanding is a no-op.
    /// </summary>
    public bool EndPairing()
    {
        lock (gate)
        {
            if (pairingHolds == 0)
            {
                return false;
            }

            pairingHolds--;
            return pairingHolds == 0;
        }
    }
}
