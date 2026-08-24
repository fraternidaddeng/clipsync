using ClipSync.Peer.Resilience;

namespace ClipSync.Tests.Resilience;

public sealed class SyncResilienceControllerTests
{
    private static readonly SyncResilienceOptions FastOptions = new()
    {
        ResumeSettleDelay = TimeSpan.FromMilliseconds(20),
        NetworkChangeThrottle = TimeSpan.FromMilliseconds(20)
    };

    private sealed class FakeSystemStateEvents : ISystemStateEvents
    {
        public event Action? SuspendingToSleep;

        public event Action? ResumedFromSuspend;

        public event Action? NetworkAddressChanged;

        public void RaiseSuspend() => SuspendingToSleep?.Invoke();

        public void RaiseResume() => ResumedFromSuspend?.Invoke();

        public void RaiseNetworkChanged() => NetworkAddressChanged?.Invoke();
    }

    private static async Task WaitUntilAsync(Func<bool> condition, TimeSpan? timeout = null)
    {
        var deadline = DateTimeOffset.UtcNow + (timeout ?? TimeSpan.FromSeconds(10));
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (condition())
            {
                return;
            }

            await Task.Delay(10);
        }

        Assert.Fail("condition not met before timeout");
    }

    [Fact]
    public async Task ResumeBurstCoalescesIntoOneRecovery()
    {
        var events = new FakeSystemStateEvents();
        var resumes = 0;
        await using var controller = new SyncResilienceController(
            events,
            onResume: _ =>
            {
                Interlocked.Increment(ref resumes);
                return Task.CompletedTask;
            },
            onNetworkChanged: _ => Task.CompletedTask,
            FastOptions);

        // Windows can deliver Resume plus ResumeAutomatic back to back.
        events.RaiseResume();
        events.RaiseResume();
        events.RaiseResume();

        await WaitUntilAsync(() => controller.ResumeRecoveryCount == 1);
        await Task.Delay(150);
        Assert.Equal(1, Volatile.Read(ref resumes));
        Assert.Equal(0, controller.NetworkRecoveryCount);
    }

    [Fact]
    public async Task NetworkChangeBurstCoalescesButLaterSignalRecoversAgain()
    {
        var events = new FakeSystemStateEvents();
        var changes = 0;
        await using var controller = new SyncResilienceController(
            events,
            onResume: _ => Task.CompletedTask,
            onNetworkChanged: _ =>
            {
                Interlocked.Increment(ref changes);
                return Task.CompletedTask;
            },
            FastOptions);

        for (var i = 0; i < 10; i++)
        {
            events.RaiseNetworkChanged();
        }

        await WaitUntilAsync(() => controller.NetworkRecoveryCount == 1);
        await Task.Delay(100);
        Assert.Equal(1, Volatile.Read(ref changes));

        // A signal after the throttle window is a fresh transition and must recover again.
        events.RaiseNetworkChanged();
        await WaitUntilAsync(() => controller.NetworkRecoveryCount == 2);
        Assert.Equal(2, Volatile.Read(ref changes));
    }

    [Fact]
    public async Task RecoveriesNeverOverlap()
    {
        var events = new FakeSystemStateEvents();
        var running = 0;
        var maxConcurrent = 0;
        var resumeBlocked = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var resumeStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        async Task TrackAsync(Task? blockOn)
        {
            var now = Interlocked.Increment(ref running);
            InterlockedMax(ref maxConcurrent, now);
            if (blockOn is not null)
            {
                await blockOn;
            }

            Interlocked.Decrement(ref running);
        }

        await using var controller = new SyncResilienceController(
            events,
            onResume: async _ =>
            {
                resumeStarted.TrySetResult();
                await TrackAsync(resumeBlocked.Task);
            },
            onNetworkChanged: _ => TrackAsync(null),
            FastOptions);

        events.RaiseResume();
        await resumeStarted.Task.WaitAsync(TimeSpan.FromSeconds(10));

        // The resume recovery is still running; a network change must queue, not overlap.
        events.RaiseNetworkChanged();
        await Task.Delay(100);
        Assert.Equal(0, controller.NetworkRecoveryCount);

        resumeBlocked.SetResult();
        await WaitUntilAsync(() => controller.NetworkRecoveryCount == 1);
        Assert.Equal(1, controller.ResumeRecoveryCount);
        Assert.Equal(1, Volatile.Read(ref maxConcurrent));
    }

    [Fact]
    public async Task CallbackFailureIsSwallowedAndLaterSignalsStillRecover()
    {
        var events = new FakeSystemStateEvents();
        var attempts = 0;
        await using var controller = new SyncResilienceController(
            events,
            onResume: _ => Task.CompletedTask,
            onNetworkChanged: _ =>
            {
                if (Interlocked.Increment(ref attempts) == 1)
                {
                    throw new InvalidOperationException("first refresh fails");
                }

                return Task.CompletedTask;
            },
            FastOptions);

        events.RaiseNetworkChanged();
        await WaitUntilAsync(() => Volatile.Read(ref attempts) == 1);
        await Task.Delay(60);
        Assert.Equal(0, controller.NetworkRecoveryCount);

        events.RaiseNetworkChanged();
        await WaitUntilAsync(() => controller.NetworkRecoveryCount == 1);
        Assert.Equal(2, Volatile.Read(ref attempts));
    }

    [Fact]
    public async Task SuspendRunsSynchronouslyWithoutSettleDelay()
    {
        var events = new FakeSystemStateEvents();
        var suspends = 0;
        await using var controller = new SyncResilienceController(
            events,
            onResume: _ => Task.CompletedTask,
            onNetworkChanged: _ => Task.CompletedTask,
            FastOptions,
            onSuspend: () => Interlocked.Increment(ref suspends));

        events.RaiseSuspend();

        // No timer, no coalescing window: the callback already ran on this thread.
        Assert.Equal(1, Volatile.Read(ref suspends));
        Assert.Equal(1, controller.SuspendSignalCount);
    }

    [Fact]
    public async Task SuspendCallbackFailureIsSwallowedAndResumeStillRecovers()
    {
        var events = new FakeSystemStateEvents();
        var resumes = 0;
        await using var controller = new SyncResilienceController(
            events,
            onResume: _ =>
            {
                Interlocked.Increment(ref resumes);
                return Task.CompletedTask;
            },
            onNetworkChanged: _ => Task.CompletedTask,
            FastOptions,
            onSuspend: () => throw new InvalidOperationException("teardown failed"));

        events.RaiseSuspend();
        Assert.Equal(0, controller.SuspendSignalCount);

        events.RaiseResume();
        await WaitUntilAsync(() => controller.ResumeRecoveryCount == 1);
        Assert.Equal(1, Volatile.Read(ref resumes));
    }

    [Fact]
    public async Task SuspendWithoutCallbackIsANoOp()
    {
        var events = new FakeSystemStateEvents();
        await using var controller = new SyncResilienceController(
            events,
            onResume: _ => Task.CompletedTask,
            onNetworkChanged: _ => Task.CompletedTask,
            FastOptions);

        events.RaiseSuspend();

        Assert.Equal(0, controller.SuspendSignalCount);
    }

    [Fact]
    public async Task SignalsAfterDisposeAreIgnored()
    {
        var events = new FakeSystemStateEvents();
        var calls = 0;
        var controller = new SyncResilienceController(
            events,
            onResume: _ =>
            {
                Interlocked.Increment(ref calls);
                return Task.CompletedTask;
            },
            onNetworkChanged: _ =>
            {
                Interlocked.Increment(ref calls);
                return Task.CompletedTask;
            },
            FastOptions);
        await controller.DisposeAsync();

        events.RaiseResume();
        events.RaiseNetworkChanged();
        await Task.Delay(120);
        Assert.Equal(0, Volatile.Read(ref calls));
    }

    [Fact]
    public async Task DisposeCancelsAndDrainsAnInFlightRecovery()
    {
        var events = new FakeSystemStateEvents();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var observedCancellation = false;
        var controller = new SyncResilienceController(
            events,
            onResume: async token =>
            {
                started.TrySetResult();
                try
                {
                    await Task.Delay(Timeout.InfiniteTimeSpan, token);
                }
                catch (OperationCanceledException)
                {
                    observedCancellation = true;
                    throw;
                }
            },
            onNetworkChanged: _ => Task.CompletedTask,
            FastOptions);

        events.RaiseResume();
        await started.Task.WaitAsync(TimeSpan.FromSeconds(10));

        // Dispose must cancel the hung recovery and wait for it to drain, not deadlock.
        await controller.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(observedCancellation);
        Assert.Equal(0, controller.ResumeRecoveryCount);
    }

    private static void InterlockedMax(ref int target, int value)
    {
        int current;
        while (value > (current = Volatile.Read(ref target)))
        {
            if (Interlocked.CompareExchange(ref target, value, current) == current)
            {
                return;
            }
        }
    }
}
