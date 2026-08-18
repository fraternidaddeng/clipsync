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

    [Fact]
    public void DuplicateSuspendActsOnce()
    {
        var system = new FakeSessionPowerEventSource();
        var win32 = new FakeSuspendResumeNotificationSource();
        using var monitor = new SessionPowerMonitor(system, win32);
        var teardowns = 0;
        var nudges = 0;
        var tags = new List<string>();
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => teardowns++,
            nudgeReconnect: () => nudges++,
            refreshStatus: () => { },
            writeDiagnostics: tags.Add);

        system.RaisePower(PowerModes.Suspend);
        win32.RaiseSuspend();
        system.RaisePower(PowerModes.Suspend);

        Assert.Equal(1, teardowns);
        Assert.Equal(0, nudges);
        Assert.Equal([SessionPowerCoordinator.PowerSuspendTag], tags);
    }

    [Fact]
    public void ResumeAutomaticThenResumeSuspendNudgesOnce()
    {
        var system = new FakeSessionPowerEventSource();
        var win32 = new FakeSuspendResumeNotificationSource();
        using var monitor = new SessionPowerMonitor(system, win32);
        var teardowns = 0;
        var nudges = 0;
        var statusRefreshes = 0;
        var tags = new List<string>();
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => teardowns++,
            nudgeReconnect: () => nudges++,
            refreshStatus: () => statusRefreshes++,
            writeDiagnostics: tags.Add);

        win32.RaiseResume();
        win32.RaiseResume();
        system.RaisePower(PowerModes.Resume);

        Assert.Equal(0, teardowns);
        Assert.Equal(1, nudges);
        Assert.Equal(1, statusRefreshes);
        Assert.Equal([SessionPowerCoordinator.PowerResumeTag], tags);
    }

    [Fact]
    public void ResumeWithoutPriorSuspendStillNudges()
    {
        var win32 = new FakeSuspendResumeNotificationSource();
        using var monitor = new SessionPowerMonitor(new FakeSessionPowerEventSource(), win32);
        var nudges = 0;
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => { },
            nudgeReconnect: () => nudges++,
            refreshStatus: () => { },
            writeDiagnostics: _ => { });

        win32.RaiseResume();

        Assert.Equal(1, nudges);
    }

    [Fact]
    public void DisposeUnregistersBothSources()
    {
        var system = new FakeSessionPowerEventSource();
        var win32 = new FakeSuspendResumeNotificationSource();
        var monitor = new SessionPowerMonitor(system, win32);
        var raised = 0;
        monitor.OnSuspend += () => raised++;

        Assert.Equal(1, system.SubscriberCount);
        Assert.Equal(1, win32.SubscriberCount);
        monitor.Dispose();
        monitor.Dispose();

        system.RaisePower(PowerModes.Suspend);
        win32.RaiseSuspend();
        Assert.Equal(0, system.SubscriberCount);
        Assert.Equal(0, win32.SubscriberCount);
        Assert.Equal(0, raised);
    }

    [Fact]
    public void BothSourcesOnOneSleepCycleTearDownAndNudgeOnce()
    {
        var system = new FakeSessionPowerEventSource();
        var win32 = new FakeSuspendResumeNotificationSource();
        using var monitor = new SessionPowerMonitor(system, win32);
        var teardowns = 0;
        var nudges = 0;
        using var coordinator = new SessionPowerCoordinator(
            monitor,
            tearDownSessions: () => teardowns++,
            nudgeReconnect: () => nudges++,
            refreshStatus: () => { },
            writeDiagnostics: _ => { });

        win32.RaiseSuspend();
        system.RaisePower(PowerModes.Suspend);
        win32.RaiseResume();
        win32.RaiseResume();
        system.RaisePower(PowerModes.Resume);

        Assert.Equal(1, teardowns);
        Assert.Equal(1, nudges);
    }

    [Theory]
    [InlineData(Win32SuspendResumeNotificationSource.PbtApmSuspend, true)]
    [InlineData(Win32SuspendResumeNotificationSource.PbtApmResumeSuspend, false)]
    [InlineData(Win32SuspendResumeNotificationSource.PbtApmResumeAutomatic, false)]
    public void Win32NotificationTypesMapToSuspendAndResume(uint type, bool isSuspend)
    {
        Assert.True(Win32SuspendResumeNotificationSource.TryMapNotification(type, out var mappedSuspend));
        Assert.Equal(isSuspend, mappedSuspend);
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

internal sealed class FakeSuspendResumeNotificationSource : ISuspendResumeNotificationSource
{
    private Action? onSuspend;
    private Action? onResume;

    public int SubscriberCount { get; private set; }

    public void Subscribe(Action onSuspend, Action onResume)
    {
        this.onSuspend += onSuspend;
        this.onResume += onResume;
        SubscriberCount++;
    }

    public void Unsubscribe()
    {
        onSuspend = null;
        onResume = null;
        SubscriberCount--;
    }

    public void Dispose() => Unsubscribe();

    public void RaiseSuspend() => onSuspend?.Invoke();

    public void RaiseResume() => onResume?.Invoke();
}
