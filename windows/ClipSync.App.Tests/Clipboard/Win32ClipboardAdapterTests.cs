using ClipSync.App.Clipboard;

namespace ClipSync.App.Tests.Clipboard;

public sealed class Win32ClipboardAdapterTests
{
    private static readonly DateTimeOffset CapturedAt =
        DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

    [Fact]
    public void StartAndStopAreIdempotent()
    {
        var window = new FakeMessageWindow();
        using var adapter = CreateAdapter(window, new FakeDataAccess());

        adapter.Start();
        adapter.Start();
        adapter.Stop();
        adapter.Stop();

        Assert.Equal(1, window.StartCount);
        Assert.Equal(1, window.StopCount);
        Assert.False(adapter.IsRunning);
    }

    [Fact]
    public async Task ConcurrentStartsRemainRunningAndCreateOneWindow()
    {
        var window = new FakeMessageWindow { BlockFirstStart = true };
        using var adapter = CreateAdapter(window, new FakeDataAccess());

        // Dedicated threads: under xUnit parallelism the shared thread pool can be
        // starved, so a queued Task.Run may not enter Start() before any small
        // timeout elapses (observed as a CI-only failure on a docs-only commit).
        var firstStart = StartOnDedicatedThread(adapter);
        Assert.True(window.StartEntered.Wait(TimeSpan.FromSeconds(30)));
        var secondStart = StartOnDedicatedThread(adapter);
        window.ContinueStart.Set();
        await Task.WhenAll(firstStart, secondStart);

        Assert.True(adapter.IsRunning);
        Assert.Equal(1, window.StartCount);
    }

    [Fact]
    public void ClipboardUpdateRaisesTextWithoutChangingContent()
    {
        var window = new FakeMessageWindow();
        var dataAccess = new FakeDataAccess
        {
            Snapshot = new ClipboardTextSnapshot("第一行\r\nsecond 😀", "notepad")
        };
        using var adapter = CreateAdapter(window, dataAccess);
        ClipboardTextChangedEventArgs? received = null;
        adapter.TextChanged += (_, eventArgs) => received = eventArgs;

        adapter.Start();
        window.RaiseClipboardUpdated();

        Assert.NotNull(received);
        Assert.Equal("第一行\r\nsecond 😀", received.Text);
        Assert.Equal("notepad", received.SourceProcess);
        Assert.Equal(CapturedAt, received.CapturedAt);
        Assert.Equal(window.Handle, dataAccess.LastReadWindow);
    }

    [Fact]
    public void NonTextClipboardDoesNotRaiseTextEvent()
    {
        var window = new FakeMessageWindow();
        using var adapter = CreateAdapter(window, new FakeDataAccess());
        var notificationCount = 0;
        adapter.TextChanged += (_, _) => notificationCount++;

        adapter.Start();
        window.RaiseClipboardUpdated();

        Assert.Equal(0, notificationCount);
    }

    [Fact]
    public void DuplicateNotificationForSameClipboardSequenceIsIgnored()
    {
        var window = new FakeMessageWindow();
        var dataAccess = new FakeDataAccess
        {
            Snapshot = new ClipboardTextSnapshot("text", null, SequenceNumber: 42)
        };
        using var adapter = CreateAdapter(window, dataAccess);
        var notificationCount = 0;
        adapter.TextChanged += (_, _) => notificationCount++;

        adapter.Start();
        window.RaiseClipboardUpdated();
        window.RaiseClipboardUpdated();

        Assert.Equal(1, notificationCount);
        Assert.Equal(2, dataAccess.ReadCount);
    }

    [Fact]
    public void ReadFailureIsReportedWithoutEscapingWindowProcedure()
    {
        var window = new FakeMessageWindow();
        var expected = new InvalidOperationException("clipboard unavailable");
        var dataAccess = new FakeDataAccess { ReadException = expected };
        using var adapter = CreateAdapter(window, dataAccess);
        ClipboardAdapterFaultEventArgs? fault = null;
        adapter.Faulted += (_, eventArgs) => fault = eventArgs;

        adapter.Start();
        var raised = Record.Exception(window.RaiseClipboardUpdated);

        Assert.Null(raised);
        Assert.NotNull(fault);
        Assert.Equal(ClipboardAdapterOperation.Read, fault.Operation);
        Assert.Equal(nameof(InvalidOperationException), fault.ErrorType);
        Assert.DoesNotContain("clipboard unavailable", fault.Exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ThrowingSubscriberDoesNotPreventOtherSubscribers()
    {
        var window = new FakeMessageWindow();
        var dataAccess = new FakeDataAccess { Snapshot = new ClipboardTextSnapshot("text", null) };
        using var adapter = CreateAdapter(window, dataAccess);
        var secondSubscriberCalled = false;
        ClipboardAdapterFaultEventArgs? fault = null;
        adapter.TextChanged += (_, _) => throw new InvalidOperationException("subscriber failure");
        adapter.TextChanged += (_, _) => secondSubscriberCalled = true;
        adapter.Faulted += (_, eventArgs) => fault = eventArgs;

        adapter.Start();
        window.RaiseClipboardUpdated();

        Assert.True(secondSubscriberCalled);
        Assert.Equal(ClipboardAdapterOperation.NotifySubscriber, fault?.Operation);
    }

    [Fact]
    public void UpdateAfterStopIsIgnored()
    {
        var window = new FakeMessageWindow();
        var dataAccess = new FakeDataAccess { Snapshot = new ClipboardTextSnapshot("text", null) };
        using var adapter = CreateAdapter(window, dataAccess);
        var notificationCount = 0;
        adapter.TextChanged += (_, _) => notificationCount++;

        adapter.Start();
        adapter.Stop();
        window.RaiseClipboardUpdated();

        Assert.Equal(0, dataAccess.ReadCount);
        Assert.Equal(0, notificationCount);
    }

    [Fact]
    public void WriteRequiresRunningAdapterAndUsesListenerHandle()
    {
        var window = new FakeMessageWindow();
        var dataAccess = new FakeDataAccess();
        using var adapter = CreateAdapter(window, dataAccess);

        Assert.Throws<InvalidOperationException>(() => adapter.WriteText("before start"));
        adapter.Start();
        adapter.WriteText("remote\r\ntext 😀");

        Assert.Equal(window.Handle, dataAccess.LastWriteWindow);
        Assert.Equal("remote\r\ntext 😀", dataAccess.LastWrittenText);
    }

    [Fact]
    public void WriteFailureIsReportedAndRethrown()
    {
        var window = new FakeMessageWindow();
        var expected = new InvalidOperationException("write failed");
        var dataAccess = new FakeDataAccess { WriteException = expected };
        using var adapter = CreateAdapter(window, dataAccess);
        ClipboardAdapterFaultEventArgs? fault = null;
        adapter.Faulted += (_, eventArgs) => fault = eventArgs;
        adapter.Start();

        var thrown = Assert.Throws<InvalidOperationException>(() => adapter.WriteText("text"));

        Assert.Same(expected, thrown);
        Assert.Equal(ClipboardAdapterOperation.Write, fault?.Operation);
        Assert.Equal(nameof(InvalidOperationException), fault?.ErrorType);
    }

    [Fact]
    public void DisposeUnsubscribesAndDisposesWindowOnce()
    {
        var window = new FakeMessageWindow();
        var dataAccess = new FakeDataAccess { Snapshot = new ClipboardTextSnapshot("text", null) };
        var adapter = CreateAdapter(window, dataAccess);
        adapter.Start();

        adapter.Dispose();
        adapter.Dispose();
        window.RaiseClipboardUpdated();

        Assert.Equal(1, window.DisposeCount);
        Assert.Equal(0, dataAccess.ReadCount);
        Assert.Throws<ObjectDisposedException>(adapter.Start);
    }

    private static Win32ClipboardAdapter CreateAdapter(
        FakeMessageWindow window,
        FakeDataAccess dataAccess) =>
        new(window, dataAccess, new FixedTimeProvider(CapturedAt));

    private static Task StartOnDedicatedThread(Win32ClipboardAdapter adapter) =>
        Task.Factory.StartNew(
            adapter.Start,
            CancellationToken.None,
            TaskCreationOptions.LongRunning,
            TaskScheduler.Default);

    private sealed class FakeMessageWindow : IClipboardMessageWindow
    {
        public event EventHandler? ClipboardUpdated;

        public nint Handle { get; } = new(123);

        public int StartCount { get; private set; }

        public int StopCount { get; private set; }

        public int DisposeCount { get; private set; }

        public bool BlockFirstStart { get; init; }

        public ManualResetEventSlim StartEntered { get; } = new();

        public ManualResetEventSlim ContinueStart { get; } = new();

        public void Start()
        {
            StartCount++;
            StartEntered.Set();
            if (BlockFirstStart && StartCount == 1)
            {
                Assert.True(ContinueStart.Wait(TimeSpan.FromSeconds(30)));
            }
        }

        public void Stop() => StopCount++;

        public void Dispose() => DisposeCount++;

        public void RaiseClipboardUpdated() => ClipboardUpdated?.Invoke(this, EventArgs.Empty);
    }

    private sealed class FakeDataAccess : IClipboardDataAccess
    {
        public ClipboardTextSnapshot? Snapshot { get; init; }

        public Exception? ReadException { get; init; }

        public Exception? WriteException { get; init; }

        public int ReadCount { get; private set; }

        public nint LastReadWindow { get; private set; }

        public nint LastWriteWindow { get; private set; }

        public string? LastWrittenText { get; private set; }

        public byte[]? LastWrittenImage { get; private set; }

        public ClipboardTextSnapshot? ReadText(nint listenerWindow)
        {
            ReadCount++;
            LastReadWindow = listenerWindow;
            if (ReadException is not null)
            {
                throw ReadException;
            }

            return Snapshot;
        }

        public void WriteText(nint listenerWindow, string text)
        {
            LastWriteWindow = listenerWindow;
            LastWrittenText = text;
            if (WriteException is not null)
            {
                throw WriteException;
            }
        }

        public void WriteImage(nint listenerWindow, byte[] pngBytes)
        {
            LastWriteWindow = listenerWindow;
            LastWrittenImage = pngBytes;
            if (WriteException is not null)
            {
                throw WriteException;
            }
        }
    }

    private sealed class FixedTimeProvider(DateTimeOffset utcNow) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => utcNow;
    }
}
