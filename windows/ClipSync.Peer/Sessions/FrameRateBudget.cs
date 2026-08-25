namespace ClipSync.Peer.Sessions;

/// <summary>
/// Fixed-window budget for inbound session frames (stage-6 hardening W5): even an
/// authenticated peer must not be able to burn this end's CPU with a tight frame loop —
/// every received frame costs a JSON parse plus a replay-window hash before its type is
/// known. The window is deliberately coarse: legitimate peaks (backlog announce bursts,
/// 16 MiB image transfers at 64 chunks each) stay far below the default budget, while a
/// flood exhausts it quickly. Single-consumer: only the session receive loop calls it.
/// </summary>
public sealed class FrameRateBudget
{
    private readonly TimeProvider clock;
    private readonly int maxFrames;
    private readonly TimeSpan window;
    private DateTimeOffset windowStart;
    private int count;

    public FrameRateBudget(TimeProvider clock, int maxFrames, TimeSpan window)
    {
        ArgumentNullException.ThrowIfNull(clock);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(maxFrames);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(window, TimeSpan.Zero);
        this.clock = clock;
        this.maxFrames = maxFrames;
        this.window = window;
        windowStart = clock.GetUtcNow();
    }

    /// <summary>Counts one frame; <c>false</c> means the session must be closed rate-limited.</summary>
    public bool TryAdmit()
    {
        var now = clock.GetUtcNow();
        if (now - windowStart >= window)
        {
            windowStart = now;
            count = 0;
        }

        return ++count <= maxFrames;
    }
}
