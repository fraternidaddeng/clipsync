namespace ClipSync.Peer.Server;

/// <summary>
/// Sliding-window admission control keyed by an opaque identity (typically a remote
/// address). Defense-in-depth for pairing confirm and WebSocket accept; the key is
/// never logged or copied into exceptions.
/// </summary>
public sealed class SlidingWindowRateLimiter
{
    private readonly TimeProvider clock;
    private readonly int maxEvents;
    private readonly TimeSpan window;
    private readonly Dictionary<string, List<DateTimeOffset>> events = new(StringComparer.Ordinal);
    private readonly object gate = new();

    public SlidingWindowRateLimiter(TimeProvider clock, int maxEvents, TimeSpan window)
    {
        ArgumentNullException.ThrowIfNull(clock);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(maxEvents);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(window, TimeSpan.Zero);
        this.clock = clock;
        this.maxEvents = maxEvents;
        this.window = window;
    }

    /// <summary>
    /// Records one event for <paramref name="key"/> when the window still has capacity.
    /// Returns <c>false</c> when the caller must refuse the request.
    /// </summary>
    public bool TryAdmit(string key)
    {
        ArgumentException.ThrowIfNullOrEmpty(key);
        lock (gate)
        {
            if (!events.TryGetValue(key, out var list))
            {
                list = [];
                events[key] = list;
            }

            Prune(list);
            if (list.Count >= maxEvents)
            {
                return false;
            }

            list.Add(clock.GetUtcNow());
            return true;
        }
    }

    private void Prune(List<DateTimeOffset> list)
    {
        var cutoff = clock.GetUtcNow() - window;
        list.RemoveAll(timestamp => timestamp < cutoff);
    }
}
