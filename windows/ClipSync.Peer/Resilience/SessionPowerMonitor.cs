using System.Diagnostics;

namespace ClipSync.Peer.Resilience;

/// <summary>
/// Test seam for one suspend/resume notification backend. Two production sources feed
/// <see cref="SessionPowerMonitor"/>: the Win32 Modern Standby registration
/// (<see cref="Win32SuspendResumeNotificationSource"/>) and the App layer's
/// SystemEvents.PowerModeChanged adapter. Subscribe/unsubscribe must keep the same
/// handler instances so dispose can detach them.
/// </summary>
public interface ISuspendResumeSource : IDisposable
{
    void Subscribe(Action onSuspend, Action onResume);

    void Unsubscribe();
}

/// <summary>
/// Merges suspend/resume callbacks from several OS sources into one deduplicated pair of
/// events. Windows delivers overlapping signals on one sleep cycle — SystemEvents
/// PowerModeChanged plus the Win32 suspend/resume registration, and PBT_APMRESUMEAUTOMATIC
/// (18) followed by PBT_APMRESUMESUSPEND (7) — so a suspended-state flag collapses duplicate
/// suspends, and resumes without an observed suspend are deduplicated inside
/// <see cref="DuplicateResumeWindow"/>. Handlers are detached in <see cref="Dispose"/> so a
/// stopped host cannot keep reacting to power transitions.
/// </summary>
public sealed class SessionPowerMonitor : IDisposable
{
    internal static readonly TimeSpan DuplicateResumeWindow = TimeSpan.FromSeconds(2);

    private readonly IReadOnlyList<ISuspendResumeSource> sources;
    private readonly bool ownsSources;
    private readonly object transitionGate = new();
    private bool isSuspended;
    private bool suspendEverSeen;
    private long lastResumeTimestamp;
    private int disposed;

    public SessionPowerMonitor(params ISuspendResumeSource[] sources)
        : this(sources, ownsSources: false)
    {
    }

    public SessionPowerMonitor(IReadOnlyList<ISuspendResumeSource> sources, bool ownsSources)
    {
        ArgumentNullException.ThrowIfNull(sources);
        if (sources.Count == 0)
        {
            throw new ArgumentException("At least one suspend/resume source is required.", nameof(sources));
        }

        this.sources = sources;
        this.ownsSources = ownsSources;
        foreach (var source in this.sources)
        {
            source.Subscribe(NotifySuspend, NotifyResume);
        }
    }

    public event Action? OnSuspend;

    public event Action? OnResume;

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
        {
            return;
        }

        foreach (var source in sources)
        {
            source.Unsubscribe();
            if (ownsSources)
            {
                source.Dispose();
            }
        }
    }

    private void NotifySuspend()
    {
        lock (transitionGate)
        {
            if (isSuspended)
            {
                return;
            }

            isSuspended = true;
            suspendEverSeen = true;
        }

        OnSuspend?.Invoke();
    }

    private void NotifyResume()
    {
        var shouldAct = false;
        lock (transitionGate)
        {
            if (isSuspended)
            {
                isSuspended = false;
                lastResumeTimestamp = Stopwatch.GetTimestamp();
                shouldAct = true;
            }
            else if (!suspendEverSeen && !IsDuplicateResume())
            {
                // Some wakes deliver only a resume (fast startup, missed suspend
                // broadcast); still recover, but collapse the 18-then-7 double fire.
                lastResumeTimestamp = Stopwatch.GetTimestamp();
                shouldAct = true;
            }
        }

        if (shouldAct)
        {
            OnResume?.Invoke();
        }
    }

    private bool IsDuplicateResume()
    {
        if (lastResumeTimestamp == 0)
        {
            return false;
        }

        return Stopwatch.GetElapsedTime(lastResumeTimestamp) < DuplicateResumeWindow;
    }
}
