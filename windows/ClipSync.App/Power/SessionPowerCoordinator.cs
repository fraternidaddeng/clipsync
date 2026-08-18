namespace ClipSync.App.Power;

/// <summary>
/// Wires <see cref="SessionPowerMonitor"/> to session teardown, reconnect nudge,
/// status refresh, and diagnostics. Lock/unlock stay no-ops for capture so a later
/// policy change is a one-line edit in <see cref="ApplySessionLockPolicy"/>.
/// </summary>
internal sealed class SessionPowerCoordinator : IDisposable
{
    internal const string PowerSuspendTag = "power_suspend";
    internal const string PowerResumeTag = "power_resume";
    internal const string SessionLockTag = "session_lock";
    internal const string SessionUnlockTag = "session_unlock";

    private readonly SessionPowerMonitor monitor;
    private readonly Action tearDownSessions;
    private readonly Action nudgeReconnect;
    private readonly Action refreshStatus;
    private readonly Action<string> writeDiagnostics;
    private int disposed;

    public SessionPowerCoordinator(
        SessionPowerMonitor monitor,
        Action tearDownSessions,
        Action nudgeReconnect,
        Action refreshStatus,
        Action<string> writeDiagnostics)
    {
        this.monitor = monitor ?? throw new ArgumentNullException(nameof(monitor));
        this.tearDownSessions = tearDownSessions ?? throw new ArgumentNullException(nameof(tearDownSessions));
        this.nudgeReconnect = nudgeReconnect ?? throw new ArgumentNullException(nameof(nudgeReconnect));
        this.refreshStatus = refreshStatus ?? throw new ArgumentNullException(nameof(refreshStatus));
        this.writeDiagnostics = writeDiagnostics ?? throw new ArgumentNullException(nameof(writeDiagnostics));

        this.monitor.OnSuspend += HandleSuspend;
        this.monitor.OnResume += HandleResume;
        this.monitor.OnSessionLock += HandleSessionLock;
        this.monitor.OnSessionUnlock += HandleSessionUnlock;
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
        {
            return;
        }

        monitor.OnSuspend -= HandleSuspend;
        monitor.OnResume -= HandleResume;
        monitor.OnSessionLock -= HandleSessionLock;
        monitor.OnSessionUnlock -= HandleSessionUnlock;
        GC.SuppressFinalize(this);
    }

    private void HandleSuspend()
    {
        writeDiagnostics(PowerSuspendTag);
        tearDownSessions();
    }

    private void HandleResume()
    {
        writeDiagnostics(PowerResumeTag);
        nudgeReconnect();
        refreshStatus();
    }

    private void HandleSessionLock()
    {
        writeDiagnostics(SessionLockTag);
        ApplySessionLockPolicy();
    }

    private void HandleSessionUnlock()
    {
        writeDiagnostics(SessionUnlockTag);
        ApplySessionUnlockPolicy();
    }

    /// <summary>Personal machine: capture stays running while locked.</summary>
    private static void ApplySessionLockPolicy()
    {
    }

    /// <summary>Personal machine: unlock does not change capture.</summary>
    private static void ApplySessionUnlockPolicy()
    {
    }
}
