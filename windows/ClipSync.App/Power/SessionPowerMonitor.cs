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

/// <summary>
/// Translates OS power and session-switch events into four callbacks. Handlers
/// must be removed in <see cref="Dispose"/> — <see cref="SystemEvents"/> holds
/// strong references and will leak the subscriber (and keep raising after shutdown)
/// if they are left attached.
/// </summary>
internal sealed class SessionPowerMonitor : IDisposable
{
    private readonly ISessionPowerEventSource source;
    private readonly PowerModeChangedEventHandler powerHandler;
    private readonly SessionSwitchEventHandler sessionHandler;
    private int disposed;

    public SessionPowerMonitor()
        : this(SystemEventsSessionPowerSource.Instance)
    {
    }

    internal SessionPowerMonitor(ISessionPowerEventSource source)
    {
        this.source = source ?? throw new ArgumentNullException(nameof(source));
        powerHandler = OnPowerModeChanged;
        sessionHandler = OnSessionSwitch;
        this.source.Subscribe(powerHandler, sessionHandler);
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
        GC.SuppressFinalize(this);
    }

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        switch (e.Mode)
        {
            case PowerModes.Suspend:
                OnSuspend?.Invoke();
                break;
            case PowerModes.Resume:
                OnResume?.Invoke();
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
}
