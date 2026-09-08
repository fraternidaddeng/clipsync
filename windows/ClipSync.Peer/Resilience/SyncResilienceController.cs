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

    /// <summary>
    /// Upper bound for one recovery pass. A pass that neither finishes nor honours its
    /// cancellation token within this window (a wedged virtual adapter pinning a socket
    /// operation, say) is abandoned: the gate frees up for the next signal and for disposal
    /// while the stuck pass is left to run detached. Without this bound a single wedged pass
    /// silently disabled every later recovery and made disposal wait forever.
    /// </summary>
    public TimeSpan RecoveryTimeout { get; init; } = TimeSpan.FromSeconds(30);
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
    private readonly Action? onRecoveryTimedOut;
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
    private long timedOutRecoveries;
    private volatile bool disposed;

    public SyncResilienceController(
        ISystemStateEvents source,
        Func<CancellationToken, Task> onResume,
        Func<CancellationToken, Task> onNetworkChanged,
        SyncResilienceOptions? options = null,
        Action? onSuspend = null,
        Action? onRecoveryTimedOut = null)
    {
        this.source = source ?? throw new ArgumentNullException(nameof(source));
        this.onResume = onResume ?? throw new ArgumentNullException(nameof(onResume));
        this.onNetworkChanged = onNetworkChanged ?? throw new ArgumentNullException(nameof(onNetworkChanged));
        this.onSuspend = onSuspend;
        this.onRecoveryTimedOut = onRecoveryTimedOut;
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

    /// <summary>Recovery passes abandoned because they outlived <see cref="SyncResilienceOptions.RecoveryTimeout"/>.</summary>
    public long TimedOutRecoveryCount => Interlocked.Read(ref timedOutRecoveries);

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

        // The pass token is cancelled on disposal and when the pass is abandoned, so a stuck
        // callback that does check its token can still unwind; the WaitAsync is the backstop
        // for one that cannot. The source is deliberately left to the GC: disposing it while
        // the abandoned callback still holds the token would tear the timer out from under it.
        var pass = CancellationTokenSource.CreateLinkedTokenSource(disposalToken);
        var abandoned = false;
        try
        {
            if (resume)
            {
                Volatile.Write(ref resumePending, 0);
                await onResume(pass.Token).WaitAsync(options.RecoveryTimeout).ConfigureAwait(false);
                Interlocked.Increment(ref resumeRecoveries);
            }
            else
            {
                Volatile.Write(ref networkPending, 0);
                await onNetworkChanged(pass.Token).WaitAsync(options.RecoveryTimeout).ConfigureAwait(false);
                Interlocked.Increment(ref networkRecoveries);
            }
        }
        catch (TimeoutException)
        {
            abandoned = true;
            pass.Cancel();
            Interlocked.Increment(ref timedOutRecoveries);
            InvokeQuietly(onRecoveryTimedOut);
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
            if (!abandoned)
            {
                pass.Dispose();
            }

            recoveryGate.Release();
        }
    }

    private static void InvokeQuietly(Action? callback)
    {
        try
        {
            callback?.Invoke();
        }
        catch
        {
            // Diagnostics hooks never get to break recovery bookkeeping.
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

        // Wait for an in-flight recovery to observe the cancellation and drain out. The pass
        // itself is bounded by RecoveryTimeout, so this cannot wait longer than that.
        if (await recoveryGate.WaitAsync(options.RecoveryTimeout).ConfigureAwait(false))
        {
            recoveryGate.Release();
        }

        disposal.Dispose();
    }
}
