using System.Windows.Threading;

namespace ClipSync.App.Clipboard;

public sealed class Win32ClipboardAdapter : IDisposable
{
    private readonly object lifecycleGate = new();
    private readonly object stateLock = new();
    private readonly IClipboardMessageWindow messageWindow;
    private readonly IClipboardDataAccess dataAccess;
    private readonly IClipboardEventDispatcher eventDispatcher;
    private readonly TimeProvider timeProvider;
    private bool isRunning;
    private bool isDisposed;
    private bool isStarting;
    private bool isStopping;
    private uint lastSequenceNumber;

    public Win32ClipboardAdapter()
        : this(
            new MessageOnlyClipboardWindow(),
            new ClipboardDataAccessor(),
            TimeProvider.System,
            new WpfClipboardEventDispatcher(Dispatcher.CurrentDispatcher))
    {
    }

    internal Win32ClipboardAdapter(
        IClipboardMessageWindow messageWindow,
        IClipboardDataAccess dataAccess,
        TimeProvider timeProvider,
        IClipboardEventDispatcher? eventDispatcher = null)
    {
        ArgumentNullException.ThrowIfNull(messageWindow);
        ArgumentNullException.ThrowIfNull(dataAccess);
        ArgumentNullException.ThrowIfNull(timeProvider);

        this.messageWindow = messageWindow;
        this.dataAccess = dataAccess;
        this.timeProvider = timeProvider;
        this.eventDispatcher = eventDispatcher ?? ImmediateClipboardEventDispatcher.Instance;
        messageWindow.ClipboardUpdated += OnClipboardUpdated;
    }

    public event EventHandler<ClipboardTextChangedEventArgs>? TextChanged;

    public event EventHandler<ClipboardAdapterFaultEventArgs>? Faulted;

    public bool IsRunning
    {
        get
        {
            lock (stateLock)
            {
                return isRunning;
            }
        }
    }

    public void Start()
    {
        lock (stateLock)
        {
            ObjectDisposedException.ThrowIf(isDisposed, this);
            if (isRunning)
            {
                return;
            }

            if (isStopping)
            {
                throw new InvalidOperationException("The clipboard adapter is changing lifecycle state.");
            }
        }

        lock (lifecycleGate)
        {
            lock (stateLock)
            {
                ObjectDisposedException.ThrowIf(isDisposed, this);
                if (isRunning)
                {
                    return;
                }

                if (isStopping)
                {
                    throw new InvalidOperationException("The clipboard adapter is changing lifecycle state.");
                }

                isStarting = true;
            }

            try
            {
                messageWindow.Start();
                lock (stateLock)
                {
                    isRunning = !isDisposed && !isStopping;
                }
            }
            finally
            {
                lock (stateLock)
                {
                    isStarting = false;
                }
            }
        }
    }

    public void Stop()
    {
        lock (stateLock)
        {
            if (isDisposed || (!isRunning && !isStarting))
            {
                return;
            }

            isRunning = false;
            isStopping = true;
        }

        try
        {
            lock (lifecycleGate)
            {
                // Wait for a start or write that already passed its state check.
            }

            messageWindow.Stop();
        }
        finally
        {
            lock (stateLock)
            {
                isStopping = false;
            }
        }
    }

    public void WriteText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);

        lock (stateLock)
        {
            ObjectDisposedException.ThrowIf(isDisposed, this);
            if (!isRunning)
            {
                throw new InvalidOperationException("The clipboard adapter must be started before writing.");
            }
        }

        var writing = false;
        try
        {
            lock (lifecycleGate)
            {
                lock (stateLock)
                {
                    ObjectDisposedException.ThrowIf(isDisposed, this);
                    if (!isRunning)
                    {
                        throw new InvalidOperationException("The clipboard adapter must be started before writing.");
                    }
                }

                writing = true;
                dataAccess.WriteText(messageWindow.Handle, text);
            }
        }
        catch (Exception exception) when (writing)
        {
            RaiseFault(ClipboardAdapterOperation.Write, exception);
            throw;
        }
    }

    public void Dispose()
    {
        lock (stateLock)
        {
            if (isDisposed)
            {
                return;
            }

            isRunning = false;
            isDisposed = true;
            isStopping = true;
        }

        messageWindow.ClipboardUpdated -= OnClipboardUpdated;
        lock (lifecycleGate)
        {
            // Wait for a start or write that already passed its state check.
        }

        messageWindow.Dispose();
    }

    private void OnClipboardUpdated(object? sender, EventArgs eventArgs)
    {
        nint listenerWindow;
        lock (stateLock)
        {
            if (!isRunning || isDisposed)
            {
                return;
            }

            listenerWindow = messageWindow.Handle;
        }

        ClipboardTextSnapshot? snapshot;
        try
        {
            snapshot = dataAccess.ReadText(listenerWindow);
        }
        catch (Exception exception)
        {
            RaiseFault(ClipboardAdapterOperation.Read, exception);
            return;
        }

        if (snapshot is null)
        {
            return;
        }

        var capturedAt = timeProvider.GetUtcNow();
        lock (stateLock)
        {
            if (!isRunning || isDisposed)
            {
                return;
            }

            if (snapshot.SequenceNumber != 0 && snapshot.SequenceNumber == lastSequenceNumber)
            {
                return;
            }

            lastSequenceNumber = snapshot.SequenceNumber;
        }

        _ = eventDispatcher.TryPost(() => DeliverSnapshot(snapshot, capturedAt));
    }

    private void DeliverSnapshot(ClipboardTextSnapshot snapshot, DateTimeOffset capturedAt)
    {
        lock (stateLock)
        {
            if (!isRunning || isDisposed)
            {
                return;
            }
        }

        var arguments = new ClipboardTextChangedEventArgs(snapshot.Text, snapshot.SourceProcess, capturedAt);
        var handlers = TextChanged;
        if (handlers is null)
        {
            return;
        }

        foreach (EventHandler<ClipboardTextChangedEventArgs> handler in handlers.GetInvocationList())
        {
            try
            {
                handler(this, arguments);
            }
            catch (Exception exception)
            {
                RaiseFault(ClipboardAdapterOperation.NotifySubscriber, exception);
            }
        }
    }

    private void RaiseFault(ClipboardAdapterOperation operation, Exception exception)
    {
        var handlers = Faulted;
        if (handlers is null)
        {
            return;
        }

        var arguments = new ClipboardAdapterFaultEventArgs(operation, exception);
        foreach (EventHandler<ClipboardAdapterFaultEventArgs> handler in handlers.GetInvocationList())
        {
            try
            {
                handler(this, arguments);
            }
            catch (Exception)
            {
                // A diagnostics subscriber cannot be allowed to unwind the Win32 window procedure.
            }
        }
    }
}
