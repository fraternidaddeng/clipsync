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
    private readonly object gate = new();

    public void RecordAuthFailure(string deviceId)
    {
        lock (gate)
        {
            if (!failures.TryGetValue(deviceId, out var list))
            {
                list = [];
                failures[deviceId] = list;
            }

            list.Add(clock.GetUtcNow());
            Prune(list);
        }
    }

    public bool IsThrottled(string deviceId)
    {
        lock (gate)
        {
            if (!failures.TryGetValue(deviceId, out var list))
            {
                return false;
            }

            Prune(list);
            return list.Count >= maxFailures;
        }
    }

    private void Prune(List<DateTimeOffset> list)
    {
        var cutoff = clock.GetUtcNow() - windowLength;
        list.RemoveAll(timestamp => timestamp < cutoff);
    }
}
