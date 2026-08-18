using System.Diagnostics;
using Microsoft.Win32;

namespace ClipSync.App.Power;

/// <summary>
/// Test seam for <see cref="SystemEvents.PowerModeChanged"/> and
/// <see cref="SystemEvents.SessionSwitch"/>. Production uses
/// <see cref="SystemEventsSessionPowerSource"/>; tests raise synthetic events.
/// Subscribe/unsubscribe must pass the same handler instances — wrapping
/// <see cref="SystemEvents"/> with a new delegate on each call would leak.
/// </summary>
internal interface ISessionPowerEventSource
{
    void Subscribe(PowerModeChangedEventHandler powerChanged, SessionSwitchEventHandler sessionSwitch);

    void Unsubscribe(PowerModeChangedEventHandler powerChanged, SessionSwitchEventHandler sessionSwitch);
}

/// <summary>
/// Test seam for Modern Standby / classic sleep callbacks
/// (<see cref="Win32SuspendResumeNotificationSource"/>). Subscribe/unsubscribe
/// must pass the same handler instances so dispose can detach them.
/// </summary>
internal interface ISuspendResumeNotificationSource : IDisposable
{
    void Subscribe(Action onSuspend, Action onResume);

    void Unsubscribe();
}

/// <summary>Forwards the real <see cref="SystemEvents"/> power and session-switch callbacks.</summary>
internal sealed class SystemEventsSessionPowerSource : ISessionPowerEventSource
{
    public static SystemEventsSessionPowerSource Instance { get; } = new();

    private SystemEventsSessionPowerSource()
    {
    }

    public void Subscribe(PowerModeChangedEventHandler powerChanged, SessionSwitchEventHandler sessionSwitch)
    {
        ArgumentNullException.ThrowIfNull(powerChanged);
        ArgumentNullException.ThrowIfNull(sessionSwitch);
        SystemEvents.PowerModeChanged += powerChanged;
        SystemEvents.SessionSwitch += sessionSwitch;
    }

    public void Unsubscribe(PowerModeChangedEventHandler powerChanged, SessionSwitchEventHandler sessionSwitch)
    {
        ArgumentNullException.ThrowIfNull(powerChanged);
        ArgumentNullException.ThrowIfNull(sessionSwitch);
        SystemEvents.PowerModeChanged -= powerChanged;
        SystemEvents.SessionSwitch -= sessionSwitch;
    }
}

/// <summary>No-op second source so existing single-source tests stay additive.</summary>
internal sealed class NullSuspendResumeNotificationSource : ISuspendResumeNotificationSource
{
    public static NullSuspendResumeNotificationSource Instance { get; } = new();

    private NullSuspendResumeNotificationSource()
    {
    }

    public void Subscribe(Action onSuspend, Action onResume)
    {
        ArgumentNullException.ThrowIfNull(onSuspend);
        ArgumentNullException.ThrowIfNull(onResume);
    }

    public void Unsubscribe()
    {
    }

    public void Dispose()
    {
    }
}

/// <summary>
/// Translates OS power and session-switch events into four callbacks. Handlers
/// must be removed in <see cref="Dispose"/> — <see cref="SystemEvents"/> holds
/// strong references and will leak the subscriber (and keep raising after shutdown)
/// if they are left attached. Both the SystemEvents source and the Win32
/// suspend/resume source feed this monitor; a suspended-state flag collapses
/// duplicate suspend/resume callbacks from the two sources (and PBT 18 then 7).
/// </summary>
internal sealed class SessionPowerMonitor : IDisposable
{
    internal static readonly TimeSpan DuplicateResumeWindow = TimeSpan.FromSeconds(2);

    private readonly ISessionPowerEventSource source;
    private readonly ISuspendResumeNotificationSource suspendResumeSource;
    private readonly bool ownsSuspendResumeSource;
    private readonly PowerModeChangedEventHandler powerHandler;
    private readonly SessionSwitchEventHandler sessionHandler;
    private readonly Action suspendHandler;
    private readonly Action resumeHandler;
    private readonly object transitionGate = new();
    private bool isSuspended;
    private bool suspendEverSeen;
    private long lastResumeTimestamp;
    private int disposed;

    public SessionPowerMonitor()
        : this(SystemEventsSessionPowerSource.Instance, new Win32SuspendResumeNotificationSource(), ownsSuspendResumeSource: true)
    {
    }

    internal SessionPowerMonitor(ISessionPowerEventSource source)
        : this(source, NullSuspendResumeNotificationSource.Instance, ownsSuspendResumeSource: false)
    {
    }

    internal SessionPowerMonitor(
        ISessionPowerEventSource source,
        ISuspendResumeNotificationSource suspendResumeSource)
        : this(source, suspendResumeSource, ownsSuspendResumeSource: false)
    {
    }

    private SessionPowerMonitor(
        ISessionPowerEventSource source,
        ISuspendResumeNotificationSource suspendResumeSource,
        bool ownsSuspendResumeSource)
    {
        this.source = source ?? throw new ArgumentNullException(nameof(source));
        this.suspendResumeSource = suspendResumeSource ?? throw new ArgumentNullException(nameof(suspendResumeSource));
        this.ownsSuspendResumeSource = ownsSuspendResumeSource;
        powerHandler = OnPowerModeChanged;
        sessionHandler = OnSessionSwitch;
        suspendHandler = NotifySuspend;
        resumeHandler = NotifyResume;
        this.source.Subscribe(powerHandler, sessionHandler);
        this.suspendResumeSource.Subscribe(suspendHandler, resumeHandler);
    }

    public event Action? OnSuspend;

    public event Action? OnResume;

    public event Action? OnSessionLock;

    public event Action? OnSessionUnlock;

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
        {
            return;
        }

        source.Unsubscribe(powerHandler, sessionHandler);
        suspendResumeSource.Unsubscribe();
        if (ownsSuspendResumeSource)
        {
            suspendResumeSource.Dispose();
        }

        GC.SuppressFinalize(this);
    }

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        switch (e.Mode)
        {
            case PowerModes.Suspend:
                NotifySuspend();
                break;
            case PowerModes.Resume:
                NotifyResume();
                break;
        }
    }

    private void OnSessionSwitch(object sender, SessionSwitchEventArgs e)
    {
        switch (e.Reason)
        {
            case SessionSwitchReason.SessionLock:
                OnSessionLock?.Invoke();
                break;
            case SessionSwitchReason.SessionUnlock:
                OnSessionUnlock?.Invoke();
                break;
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
