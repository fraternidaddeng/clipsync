using ClipSync.App.Power;
using Microsoft.Win32;

namespace ClipSync.App.Tests.Power;

public sealed class SessionPowerMonitorTests
{
    [Fact]
    public void SuspendTearsDownSessionsOnce()
    {
        var source = new FakeSessionPowerEventSource();
        using var monitor = new SessionPowerMonitor(source);
        var teardowns = 0;
        var nudges = 0;
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => teardowns++,
            nudgeReconnect: () => nudges++,
            refreshStatus: () => { },
            writeDiagnostics: _ => { });

        source.RaisePower(PowerModes.Suspend);

        Assert.Equal(1, teardowns);
        Assert.Equal(0, nudges);
    }

    [Fact]
    public void ResumeTriggersAReconnectNudge()
    {
        var source = new FakeSessionPowerEventSource();
        using var monitor = new SessionPowerMonitor(source);
        var teardowns = 0;
        var nudges = 0;
        var statusRefreshes = 0;
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => teardowns++,
            nudgeReconnect: () => nudges++,
            refreshStatus: () => statusRefreshes++,
            writeDiagnostics: _ => { });

        source.RaisePower(PowerModes.Resume);

        Assert.Equal(0, teardowns);
        Assert.Equal(1, nudges);
        Assert.Equal(1, statusRefreshes);
    }

    [Fact]
    public void DisposeUnsubscribesFromTheEventSource()
    {
        var source = new FakeSessionPowerEventSource();
        var monitor = new SessionPowerMonitor(source);
        var raised = 0;
        monitor.OnSuspend += () => raised++;

        Assert.Equal(1, source.SubscriberCount);
        monitor.Dispose();
        monitor.Dispose();

        source.RaisePower(PowerModes.Suspend);
        Assert.Equal(0, source.SubscriberCount);
        Assert.Equal(0, raised);
    }

    [Fact]
    public void TransitionsWriteStableDiagnosticsTags()
    {
        var source = new FakeSessionPowerEventSource();
        using var monitor = new SessionPowerMonitor(source);
        var tags = new List<string>();
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => { },
            nudgeReconnect: () => { },
            refreshStatus: () => { },
            writeDiagnostics: tags.Add);

        source.RaisePower(PowerModes.Suspend);
        source.RaisePower(PowerModes.Resume);
        source.RaisePower(PowerModes.StatusChange);
        source.RaiseSession(SessionSwitchReason.SessionLock);
        source.RaiseSession(SessionSwitchReason.SessionUnlock);
        source.RaiseSession(SessionSwitchReason.ConsoleConnect);

        Assert.Equal(
            [
                SessionPowerCoordinator.PowerSuspendTag,
                SessionPowerCoordinator.PowerResumeTag,
                SessionPowerCoordinator.SessionLockTag,
                SessionPowerCoordinator.SessionUnlockTag
            ],
            tags);
    }

    [Fact]
    public void SessionLockAndUnlockDoNotTearDownOrNudge()
    {
        var source = new FakeSessionPowerEventSource();
        using var monitor = new SessionPowerMonitor(source);
        var teardowns = 0;
        var nudges = 0;
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => teardowns++,
            nudgeReconnect: () => nudges++,
            refreshStatus: () => { },
            writeDiagnostics: _ => { });

        source.RaiseSession(SessionSwitchReason.SessionLock);
        source.RaiseSession(SessionSwitchReason.SessionUnlock);

        Assert.Equal(0, teardowns);
        Assert.Equal(0, nudges);
    }
}

internal sealed class FakeSessionPowerEventSource : ISessionPowerEventSource
{
    private PowerModeChangedEventHandler? powerChanged;
    private SessionSwitchEventHandler? sessionSwitch;

    public int SubscriberCount { get; private set; }

    public void Subscribe(PowerModeChangedEventHandler power, SessionSwitchEventHandler session)
    {
        powerChanged += power;
        sessionSwitch += session;
        SubscriberCount++;
    }

    public void Unsubscribe(PowerModeChangedEventHandler power, SessionSwitchEventHandler session)
    {
        powerChanged -= power;
        sessionSwitch -= session;
        SubscriberCount--;
    }

    public void RaisePower(PowerModes mode) =>
        powerChanged?.Invoke(this, new PowerModeChangedEventArgs(mode));

    public void RaiseSession(SessionSwitchReason reason) =>
        sessionSwitch?.Invoke(this, new SessionSwitchEventArgs(reason));
}
