using System.Net.NetworkInformation;
using ClipSync.Peer.Resilience;
using Microsoft.Win32;

namespace ClipSync.App.Sync;

/// <summary>
/// Adapts the real Windows signals onto <see cref="ISystemStateEvents"/>:
/// SystemEvents.PowerModeChanged (resume from suspend/hibernate) and
/// NetworkChange.NetworkAddressChanged (interface churn). Both raise on worker threads.
/// </summary>
public sealed class WindowsSystemStateEvents : ISystemStateEvents, IDisposable
{
    private readonly PowerModeChangedEventHandler powerModeChanged;
    private readonly NetworkAddressChangedEventHandler addressChanged;
    private bool disposed;

    public WindowsSystemStateEvents()
    {
        powerModeChanged = (_, args) =>
        {
            if (args.Mode == PowerModes.Resume)
            {
                ResumedFromSuspend?.Invoke();
            }
        };
        addressChanged = (_, _) => NetworkAddressChanged?.Invoke();
        SystemEvents.PowerModeChanged += powerModeChanged;
        NetworkChange.NetworkAddressChanged += addressChanged;
    }

    public event Action? ResumedFromSuspend;

    public event Action? NetworkAddressChanged;

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        SystemEvents.PowerModeChanged -= powerModeChanged;
        NetworkChange.NetworkAddressChanged -= addressChanged;
    }
}
