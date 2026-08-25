namespace ClipSync.Peer.Resilience;

public sealed record SyncResilienceOptions
{
    /// <summary>
    /// How long to wait after a resume signal before recovering. Interfaces need a moment
    /// to re-associate after wake; recovering instantly would just see a dead network.
    /// </summary>
    public TimeSpan ResumeSettleDelay { get; init; } = TimeSpan.FromSeconds(3);

    /// <summary>
    /// Coalescing window for address-change signals. Windows fires NetworkAddressChanged
    /// in bursts (one per interface transition); one refresh per window is enough because
    /// the recovery callback always reads the *current* interface state.
    /// </summary>
    public TimeSpan NetworkChangeThrottle { get; init; } = TimeSpan.FromSeconds(2);
}

/// <summary>
/// Turns raw system signals (resume from suspend, network address churn) into serialized,
/// coalesced recovery calls. Guarantees: recovery callbacks never overlap, signal bursts
/// collapse into one call per window, callback failures never propagate (the next signal
/// retries), and dispose waits for any in-flight recovery to finish. The suspend signal is
/// the one exception to coalescing: it runs <c>onSuspend</c> synchronously on the event
/// thread, because the machine is about to sleep and a delayed teardown would never run.
/// </summary>
public sealed class SyncResilienceController : IAsyncDisposable
{
    private readonly ISystemStateEvents source;
    private readonly Func<CancellationToken, Task> onResume;
    private readonly Func<CancellationToken, Task> onNetworkChanged;
    private readonly Action? onSuspend;
    private readonly SyncResilienceOptions options;
    private readonly CancellationTokenSource disposal = new();
    private readonly CancellationToken disposalToken;
    private readonly SemaphoreSlim recoveryGate = new(1, 1);
    private readonly Timer resumeTimer;
    private readonly Timer networkTimer;
    private int resumePending;
    private int networkPending;
    private long resumeRecoveries;
    private long networkRecoveries;
    private long suspendSignals;
    private volatile bool disposed;

    public SyncResilienceController(
        ISystemStateEvents source,
        Func<CancellationToken, Task> onResume,
        Func<CancellationToken, Task> onNetworkChanged,
        SyncResilienceOptions? options = null,
        Action? onSuspend = null)
    {
        this.source = source ?? throw new ArgumentNullException(nameof(source));
        this.onResume = onResume ?? throw new ArgumentNullException(nameof(onResume));
        this.onNetworkChanged = onNetworkChanged ?? throw new ArgumentNullException(nameof(onNetworkChanged));
        this.onSuspend = onSuspend;
        this.options = options ?? new SyncResilienceOptions();
        disposalToken = disposal.Token;
        resumeTimer = new Timer(_ => _ = RunRecoveryAsync(resume: true), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        networkTimer = new Timer(_ => _ = RunRecoveryAsync(resume: false), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        source.SuspendingToSleep += OnSuspendingToSleep;
        source.ResumedFromSuspend += OnResumedFromSuspend;
        source.NetworkAddressChanged += OnNetworkAddressChanged;
    }

    /// <summary>Completed resume recoveries (callback ran without throwing).</summary>
    public long ResumeRecoveryCount => Interlocked.Read(ref resumeRecoveries);

    /// <summary>Completed network-change recoveries (callback ran without throwing).</summary>
    public long NetworkRecoveryCount => Interlocked.Read(ref networkRecoveries);

    /// <summary>Suspend signals whose synchronous callback ran without throwing.</summary>
    public long SuspendSignalCount => Interlocked.Read(ref suspendSignals);

    /// <summary>
    /// Runs inline (no settle delay, no coalescing timer): the OS grants only a short window
    /// before sleep, so the teardown must happen on this callback. Failures are swallowed —
    /// a failed pre-sleep teardown just means dead sockets, which the resume pass replaces.
    /// </summary>
    private void OnSuspendingToSleep()
    {
        if (disposed || onSuspend is null)
        {
            return;
        }

        try
        {
            onSuspend();
            Interlocked.Increment(ref suspendSignals);
        }
        catch
        {
            // Never propagate into the OS power callback.
        }
    }

    private void OnResumedFromSuspend() =>
        Schedule(ref resumePending, resumeTimer, options.ResumeSettleDelay);

    private void OnNetworkAddressChanged() =>
        Schedule(ref networkPending, networkTimer, options.NetworkChangeThrottle);

    /// <summary>
    /// Arms the timer on the first signal of a burst; later signals inside the window are
    /// absorbed by the pending flag. The flag clears when the recovery callback starts, so
    /// a signal arriving mid-recovery schedules exactly one follow-up pass.
    /// </summary>
    private void Schedule(ref int pendingFlag, Timer timer, TimeSpan delay)
    {
        if (disposed || Interlocked.CompareExchange(ref pendingFlag, 1, 0) != 0)
        {
            return;
        }

        try
        {
            timer.Change(delay, Timeout.InfiniteTimeSpan);
        }
        catch (ObjectDisposedException)
        {
            // Raced dispose; nothing left to recover.
        }
    }

    private async Task RunRecoveryAsync(bool resume)
    {
        try
        {
            await recoveryGate.WaitAsync(disposalToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        try
        {
            if (resume)
            {
                Volatile.Write(ref resumePending, 0);
                await onResume(disposalToken).ConfigureAwait(false);
                Interlocked.Increment(ref resumeRecoveries);
            }
            else
            {
                Volatile.Write(ref networkPending, 0);
                await onNetworkChanged(disposalToken).ConfigureAwait(false);
                Interlocked.Increment(ref networkRecoveries);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch
        {
            // Recovery must never take the host down; the next signal (or the periodic
            // beacon timer in the host) retries with fresh state.
        }
        finally
        {
            recoveryGate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        source.SuspendingToSleep -= OnSuspendingToSleep;
        source.ResumedFromSuspend -= OnResumedFromSuspend;
        source.NetworkAddressChanged -= OnNetworkAddressChanged;
        await disposal.CancelAsync().ConfigureAwait(false);
        await resumeTimer.DisposeAsync().ConfigureAwait(false);
        await networkTimer.DisposeAsync().ConfigureAwait(false);

        // Wait for an in-flight recovery to observe the cancellation and drain out.
        await recoveryGate.WaitAsync().ConfigureAwait(false);
        recoveryGate.Release();
        disposal.Dispose();
    }
}
