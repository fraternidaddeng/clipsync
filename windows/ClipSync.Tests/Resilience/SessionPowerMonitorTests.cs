using ClipSync.Peer.Resilience;

namespace ClipSync.Tests.Resilience;

/// <summary>
/// The monitor merges overlapping OS suspend/resume signals (SystemEvents plus the Win32
/// Modern Standby registration, PBT 18 then 7) into exactly one suspend and one resume per
/// sleep cycle. Fake sources stand in for both backends.
/// </summary>
public sealed class SessionPowerMonitorTests
{
    [Fact]
    public void SuspendActsOnceAndResumeActsOnce()
    {
        var source = new FakeSuspendResumeSource();
        using var monitor = new SessionPowerMonitor(source);
        var suspends = 0;
        var resumes = 0;
        monitor.OnSuspend += () => suspends++;
        monitor.OnResume += () => resumes++;

        source.RaiseSuspend();
        source.RaiseResume();

        Assert.Equal(1, suspends);
        Assert.Equal(1, resumes);
    }

    [Fact]
    public void DuplicateSuspendFromTwoSourcesActsOnce()
    {
        var system = new FakeSuspendResumeSource();
        var win32 = new FakeSuspendResumeSource();
        using var monitor = new SessionPowerMonitor(system, win32);
        var suspends = 0;
        monitor.OnSuspend += () => suspends++;

        system.RaiseSuspend();
        win32.RaiseSuspend();
        system.RaiseSuspend();

        Assert.Equal(1, suspends);
    }

    [Fact]
    public void ResumeAutomaticThenResumeSuspendActsOnce()
    {
        var system = new FakeSuspendResumeSource();
        var win32 = new FakeSuspendResumeSource();
        using var monitor = new SessionPowerMonitor(system, win32);
        var resumes = 0;
        monitor.OnResume += () => resumes++;

        // PBT_APMRESUMEAUTOMATIC (18) then PBT_APMRESUMESUSPEND (7), then the legacy
        // SystemEvents Resume — one wake, one recovery.
        win32.RaiseResume();
        win32.RaiseResume();
        system.RaiseResume();

        Assert.Equal(1, resumes);
    }

    [Fact]
    public void ResumeWithoutPriorSuspendStillActs()
    {
        var source = new FakeSuspendResumeSource();
        using var monitor = new SessionPowerMonitor(source);
        var resumes = 0;
        monitor.OnResume += () => resumes++;

        source.RaiseResume();

        Assert.Equal(1, resumes);
    }

    [Fact]
    public void BothSourcesOnOneSleepCycleActOnceEachWay()
    {
        var system = new FakeSuspendResumeSource();
        var win32 = new FakeSuspendResumeSource();
        using var monitor = new SessionPowerMonitor(system, win32);
        var suspends = 0;
        var resumes = 0;
        monitor.OnSuspend += () => suspends++;
        monitor.OnResume += () => resumes++;

        win32.RaiseSuspend();
        system.RaiseSuspend();
        win32.RaiseResume();
        win32.RaiseResume();
        system.RaiseResume();

        Assert.Equal(1, suspends);
        Assert.Equal(1, resumes);
    }

    [Fact]
    public void SecondSleepCycleActsAgain()
    {
        var source = new FakeSuspendResumeSource();
        using var monitor = new SessionPowerMonitor(source);
        var suspends = 0;
        var resumes = 0;
        monitor.OnSuspend += () => suspends++;
        monitor.OnResume += () => resumes++;

        source.RaiseSuspend();
        source.RaiseResume();
        source.RaiseSuspend();
        source.RaiseResume();

        Assert.Equal(2, suspends);
        Assert.Equal(2, resumes);
    }

    [Fact]
    public void DisposeUnregistersEverySource()
    {
        var system = new FakeSuspendResumeSource();
        var win32 = new FakeSuspendResumeSource();
        var monitor = new SessionPowerMonitor(system, win32);
        var raised = 0;
        monitor.OnSuspend += () => raised++;

        Assert.Equal(1, system.SubscriberCount);
        Assert.Equal(1, win32.SubscriberCount);
        monitor.Dispose();
        monitor.Dispose();

        system.RaiseSuspend();
        win32.RaiseSuspend();
        Assert.Equal(0, system.SubscriberCount);
        Assert.Equal(0, win32.SubscriberCount);
        Assert.Equal(0, raised);
    }

    [Fact]
    public void OwnedSourcesAreDisposedWithTheMonitor()
    {
        var source = new FakeSuspendResumeSource();
        var monitor = new SessionPowerMonitor([source], ownsSources: true);

        monitor.Dispose();

        Assert.True(source.Disposed);
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

    [Theory]
    [InlineData(0u)]
    [InlineData(10u)]
    [InlineData(32787u)]
    public void UnknownWin32NotificationTypesAreIgnored(uint type)
    {
        Assert.False(Win32SuspendResumeNotificationSource.TryMapNotification(type, out _));
    }
}

internal sealed class FakeSuspendResumeSource : ISuspendResumeSource
{
    private Action? onSuspend;
    private Action? onResume;

    public int SubscriberCount { get; private set; }

    public bool Disposed { get; private set; }

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

    public void Dispose()
    {
        Disposed = true;
        Unsubscribe();
    }

    public void RaiseSuspend() => onSuspend?.Invoke();

    public void RaiseResume() => onResume?.Invoke();
}
