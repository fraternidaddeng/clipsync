using ClipSync.Peer.Sessions;

namespace ClipSync.Peer.Server;

/// <summary>
/// Sliding-window throttle for failed authentication attempts, keyed by claimed device ID.
/// Protocol section 3 requires rate limiting without logging proof material.
/// </summary>
public sealed class AuthThrottle(TimeProvider clock, int maxFailures = 5, TimeSpan? window = null) : IAuthFailureSink
{
    private readonly TimeSpan windowLength = window ?? TimeSpan.FromSeconds(30);
    private readonly Dictionary<string, List<DateTimeOffset>> failures = new(StringComparer.Ordinal);

    /// <summary>Devices already announced as locked out, so the transition event fires once per episode.</summary>
    private readonly HashSet<string> announced = new(StringComparer.Ordinal);
    private readonly object gate = new();

    /// <summary>
    /// Raised once when a device first crosses into the locked-out state (its failures reach the
    /// threshold within the window), and again only after it has recovered and re-locked. Fired
    /// outside the lock on the thread that recorded the failure. Carries the claimed device id
    /// only — never proof material — so the App layer can surface the lockout to the user.
    /// </summary>
    public event Action<string>? DeviceLockedOut;

    public void RecordAuthFailure(string deviceId)
    {
        var lockedOutNow = false;
        lock (gate)
        {
            if (!failures.TryGetValue(deviceId, out var list))
            {
                list = [];
                failures[deviceId] = list;
            }

            list.Add(clock.GetUtcNow());
            Prune(list);
            if (list.Count >= maxFailures)
            {
                // announced.Add returns true only on the transition into the locked-out state.
                lockedOutNow = announced.Add(deviceId);
            }
            else
            {
                announced.Remove(deviceId);
            }
        }

        if (lockedOutNow)
        {
            DeviceLockedOut?.Invoke(deviceId);
        }
    }

    public bool IsThrottled(string deviceId)
    {
        lock (gate)
        {
            if (!failures.TryGetValue(deviceId, out var list))
            {
                announced.Remove(deviceId);
                return false;
            }

            Prune(list);
            var throttled = list.Count >= maxFailures;
            if (!throttled)
            {
                // Window drained: a future burst re-announces a fresh lockout.
                announced.Remove(deviceId);
            }

            return throttled;
        }
    }

    /// <summary>
    /// Snapshot of device ids locked out right now (window pruned first). Drives the diagnostics
    /// viewer and any status surface; returns claimed device ids only, never proof material.
    /// </summary>
    public IReadOnlyList<string> ThrottledDevices()
    {
        lock (gate)
        {
            var throttled = new List<string>();
            foreach (var (deviceId, list) in failures)
            {
                Prune(list);
                if (list.Count >= maxFailures)
                {
                    throttled.Add(deviceId);
                }
            }

            return throttled;
        }
    }

    private void Prune(List<DateTimeOffset> list)
    {
        var cutoff = clock.GetUtcNow() - windowLength;
        list.RemoveAll(timestamp => timestamp < cutoff);
    }
}
