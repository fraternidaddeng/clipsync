using System.Net.NetworkInformation;
using ClipSync.Peer.Resilience;
using Microsoft.Win32;

namespace ClipSync.App.Sync;

/// <summary>
/// Adapts the real Windows signals onto <see cref="ISystemStateEvents"/>. Suspend/resume
/// comes from a <see cref="SessionPowerMonitor"/> fed by two sources — the legacy
/// SystemEvents.PowerModeChanged broadcast and the Win32
/// PowerRegisterSuspendResumeNotification registration, which is the only one Modern
/// Standby (S0) machines reliably deliver — deduplicated so one sleep cycle raises one
/// suspend and one resume. Network churn stays on NetworkChange.NetworkAddressChanged.
/// All events raise on worker threads.
/// </summary>
public sealed class WindowsSystemStateEvents : ISystemStateEvents, IDisposable
{
    private readonly SessionPowerMonitor powerMonitor;
    private readonly NetworkAddressChangedEventHandler addressChanged;
    private bool disposed;

    public WindowsSystemStateEvents()
    {
        powerMonitor = new SessionPowerMonitor(
            [new SystemEventsSuspendResumeSource(), new Win32SuspendResumeNotificationSource()],
            ownsSources: true);
        powerMonitor.OnSuspend += () => SuspendingToSleep?.Invoke();
        powerMonitor.OnResume += () => ResumedFromSuspend?.Invoke();
        addressChanged = (_, _) => NetworkAddressChanged?.Invoke();
        NetworkChange.NetworkAddressChanged += addressChanged;
    }

    public event Action? SuspendingToSleep;

    public event Action? ResumedFromSuspend;

    public event Action? NetworkAddressChanged;

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        powerMonitor.Dispose();
        NetworkChange.NetworkAddressChanged -= addressChanged;
    }
}

/// <summary>
/// The classic PBT broadcast path (covers S3 sleep and hibernate). SystemEvents holds
/// strong handler references, so unsubscribe must pass the same delegate instance.
/// </summary>
internal sealed class SystemEventsSuspendResumeSource : ISuspendResumeSource
{
    private readonly PowerModeChangedEventHandler handler;
    private Action? onSuspend;
    private Action? onResume;
    private bool subscribed;

    public SystemEventsSuspendResumeSource()
    {
        handler = OnPowerModeChanged;
    }

    public void Subscribe(Action onSuspend, Action onResume)
    {
        ArgumentNullException.ThrowIfNull(onSuspend);
        ArgumentNullException.ThrowIfNull(onResume);
        this.onSuspend = onSuspend;
        this.onResume = onResume;
        if (!subscribed)
        {
            SystemEvents.PowerModeChanged += handler;
            subscribed = true;
        }
    }

    public void Unsubscribe()
    {
        if (subscribed)
        {
            SystemEvents.PowerModeChanged -= handler;
            subscribed = false;
        }

        onSuspend = null;
        onResume = null;
    }

    public void Dispose() => Unsubscribe();

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        switch (e.Mode)
        {
            case PowerModes.Suspend:
                onSuspend?.Invoke();
                break;
            case PowerModes.Resume:
                onResume?.Invoke();
                break;
        }
    }
}
