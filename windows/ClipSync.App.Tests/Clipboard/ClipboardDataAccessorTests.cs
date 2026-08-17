using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using ClipSync.App.Clipboard;

namespace ClipSync.App.Tests.Clipboard;

public sealed class ClipboardDataAccessorTests
{
    [Fact]
    public void ReadRetriesBusyClipboardAndPreservesUnicodeText()
    {
        using var nativeApi = new FakeClipboardNativeApi
        {
            OpenResults = new Queue<bool>([false, false, true]),
            ClipboardText = "第一行\r\nsecond line\nemoji 😀",
            OwnerWindow = new nint(77),
            OwnerProcessId = 1234
        };
        var delay = new RecordingDelay();
        var ownerResolver = new FixedOwnerResolver("writer");
        var accessor = new ClipboardDataAccessor(
            nativeApi,
            ownerResolver,
            delay,
            openAttempts: 5,
            openRetryDelay: TimeSpan.FromMilliseconds(7));

        var result = accessor.ReadText(new nint(42));

        Assert.NotNull(result);
        Assert.Equal(nativeApi.ClipboardText, result.Text);
        Assert.Equal("writer", result.SourceProcess);
        Assert.Equal((uint)1234, ownerResolver.LastProcessId);
        Assert.Equal(3, nativeApi.OpenCount);
        Assert.Equal([TimeSpan.FromMilliseconds(7), TimeSpan.FromMilliseconds(7)], delay.Delays);
        Assert.Equal(1, nativeApi.CloseCount);
        Assert.Equal(new nint(42), nativeApi.LastOpenOwner);
    }

    [Fact]
    public void DefaultOpenRetryWindowStaysBelowCaptureLatencyBudget()
    {
        using var nativeApi = new FakeClipboardNativeApi
        {
            OpenResults = new Queue<bool>([false, false, false, false, false])
        };
        var delay = new RecordingDelay();
        var accessor = new ClipboardDataAccessor(nativeApi, new FixedOwnerResolver(null), delay);

        _ = Assert.Throws<Win32Exception>(() => accessor.ReadText(nint.Zero));

        Assert.Equal(4, delay.Delays.Count);
        Assert.True(delay.Delays.Aggregate(TimeSpan.Zero, (total, item) => total + item) < TimeSpan.FromMilliseconds(500));
    }

    [Fact]
    public void ReadReturnsNullForNonTextClipboardAndAlwaysCloses()
    {
        using var nativeApi = new FakeClipboardNativeApi { FormatAvailable = false };
        var accessor = CreateAccessor(nativeApi);

        var result = accessor.ReadText(nint.Zero);

        Assert.Null(result);
        Assert.Equal(1, nativeApi.CloseCount);
        Assert.Equal(0, nativeApi.GetClipboardDataCount);
    }

    [Fact]
    public void ReadRejectsOversizedNativeAllocationBeforeLockingIt()
    {
        using var nativeApi = new FakeClipboardNativeApi
        {
            ClipboardText = "x",
            ClipboardDataSizeOverride = ClipboardDataAccessor.MaximumUnicodeTextBytes + sizeof(char)
        };
        var accessor = CreateAccessor(nativeApi);

        _ = Assert.Throws<InvalidDataException>(() => accessor.ReadText(nint.Zero));

        Assert.Equal(0, nativeApi.GlobalLockCount);
        Assert.Equal(1, nativeApi.CloseCount);
    }

    [Fact]
    public void ReadRejectsOddSizedOrUnterminatedNativeText()
    {
        using var oddSize = new FakeClipboardNativeApi { ClipboardText = "x", ClipboardDataSizeOverride = 3 };
        using var unterminated = new FakeClipboardNativeApi { ClipboardBytes = Encoding.Unicode.GetBytes("text") };

        _ = Assert.Throws<InvalidDataException>(() => CreateAccessor(oddSize).ReadText(nint.Zero));
        _ = Assert.Throws<InvalidDataException>(() => CreateAccessor(unterminated).ReadText(nint.Zero));
    }

    [Fact]
    public void ReadThrowsAfterBoundedOpenAttempts()
    {
        using var nativeApi = new FakeClipboardNativeApi
        {
            OpenResults = new Queue<bool>([false, false, false]),
            LastError = 5
        };
        var delay = new RecordingDelay();
        var accessor = new ClipboardDataAccessor(
            nativeApi,
            new FixedOwnerResolver(null),
            delay,
            openAttempts: 3,
            openRetryDelay: TimeSpan.FromMilliseconds(10));

        var exception = Assert.Throws<Win32Exception>(() => accessor.ReadText(nint.Zero));

        Assert.Equal(5, exception.NativeErrorCode);
        Assert.Equal(3, nativeApi.OpenCount);
        Assert.Equal(2, delay.Delays.Count);
        Assert.Equal(0, nativeApi.CloseCount);
    }

    [Fact]
    public void WriteUsesUnicodeTextAndTransfersMemoryOwnership()
    {
        using var nativeApi = new FakeClipboardNativeApi();
        var accessor = CreateAccessor(nativeApi);

        accessor.WriteText(new nint(99), "remote\r\n文本 😀");

        Assert.True(nativeApi.EmptyCalled);
        Assert.Equal(ClipboardDataAccessor.UnicodeTextFormat, nativeApi.SetFormat);
        Assert.Equal("remote\r\n文本 😀", nativeApi.SetText);
        Assert.Equal(0, nativeApi.GlobalFreeCount);
        Assert.Equal(1, nativeApi.CloseCount);
        Assert.Equal(new nint(99), nativeApi.LastOpenOwner);
    }

    [Fact]
    public void WriteFailureReleasesUnownedMemoryAndClosesClipboard()
    {
        using var nativeApi = new FakeClipboardNativeApi { FailSetClipboardData = true, LastError = 8 };
        var accessor = CreateAccessor(nativeApi);

        var exception = Assert.Throws<Win32Exception>(() => accessor.WriteText(nint.Zero, "text"));

        Assert.Equal(8, exception.NativeErrorCode);
        Assert.Equal(1, nativeApi.GlobalFreeCount);
        Assert.Equal(1, nativeApi.CloseCount);
    }

    [Fact]
    public void WriteRejectsEmbeddedNullBeforeOpeningClipboard()
    {
        using var nativeApi = new FakeClipboardNativeApi();

        _ = Assert.Throws<ArgumentException>(() => CreateAccessor(nativeApi).WriteText(nint.Zero, "prefix\0suffix"));

        Assert.Equal(0, nativeApi.OpenCount);
    }

    [Fact]
    public void OwnerResolverReturnsCurrentProcessName()
    {
        var nativeApi = new FakeClipboardNativeApi
        {
            OwnerWindow = new nint(55),
            OwnerProcessId = checked((uint)Environment.ProcessId)
        };
        var resolver = new ProcessClipboardOwnerResolver();

        var result = resolver.ResolveProcess(checked((uint)Environment.ProcessId));

        Assert.Equal(System.Diagnostics.Process.GetCurrentProcess().ProcessName, result);
    }

    private static ClipboardDataAccessor CreateAccessor(FakeClipboardNativeApi nativeApi) =>
        new(nativeApi, new FixedOwnerResolver(null), new RecordingDelay());

    private sealed class FixedOwnerResolver(string? processName) : IClipboardOwnerResolver
    {
        public uint LastProcessId { get; private set; }

        public string? ResolveProcess(uint processId)
        {
            LastProcessId = processId;
            return processName;
        }
    }

    private sealed class RecordingDelay : IClipboardRetryDelay
    {
        public List<TimeSpan> Delays { get; } = [];

        public void Wait(TimeSpan delay) => Delays.Add(delay);
    }

    private sealed class FakeClipboardNativeApi : IClipboardNativeApi, IDisposable
    {
        private readonly HashSet<nint> allocations = [];
        private nint clipboardData;
        private string clipboardText = "text";

        public Queue<bool> OpenResults { get; init; } = new([true]);

        public bool FormatAvailable { get; init; } = true;

        public string ClipboardText
        {
            get => clipboardText;
            set
            {
                var bytes = Encoding.Unicode.GetBytes(value + '\0');
                clipboardData = Marshal.AllocHGlobal(bytes.Length);
                Marshal.Copy(bytes, 0, clipboardData, bytes.Length);
                allocations.Add(clipboardData);
                ClipboardDataSize = checked((nuint)bytes.Length);
                clipboardText = value;
            }
        }

        public byte[] ClipboardBytes
        {
            init
            {
                clipboardData = Marshal.AllocHGlobal(value.Length);
                Marshal.Copy(value, 0, clipboardData, value.Length);
                allocations.Add(clipboardData);
                ClipboardDataSize = checked((nuint)value.Length);
            }
        }

        public nuint? ClipboardDataSizeOverride { get; init; }

        public nuint ClipboardDataSize { get; private set; }

        public bool FailSetClipboardData { get; init; }

        public int LastError { get; init; } = 5;

        public nint OwnerWindow { get; init; }

        public uint OwnerProcessId { get; init; }

        public uint SequenceNumber { get; init; }

        public int OpenCount { get; private set; }

        public int CloseCount { get; private set; }

        public int GetClipboardDataCount { get; private set; }

        public int GlobalFreeCount { get; private set; }

        public int GlobalLockCount { get; private set; }

        public nint LastOpenOwner { get; private set; }

        public bool EmptyCalled { get; private set; }

        public uint SetFormat { get; private set; }

        public string? SetText { get; private set; }

        public bool OpenClipboard(nint ownerWindow)
        {
            OpenCount++;
            LastOpenOwner = ownerWindow;
            return OpenResults.Count == 0 || OpenResults.Dequeue();
        }

        public bool CloseClipboard()
        {
            CloseCount++;
            return true;
        }

        public bool EmptyClipboard()
        {
            EmptyCalled = true;
            return true;
        }

        public bool IsClipboardFormatAvailable(uint format) => FormatAvailable;

        public nint GetClipboardData(uint format)
        {
            GetClipboardDataCount++;
            return clipboardData;
        }

        public nint SetClipboardData(uint format, nint memory)
        {
            SetFormat = format;
            SetText = Marshal.PtrToStringUni(memory);
            return FailSetClipboardData ? nint.Zero : memory;
        }

        public nint GetClipboardOwner() => OwnerWindow;

        public uint GetClipboardSequenceNumber() => SequenceNumber;

        public uint GetWindowThreadProcessId(nint window, out uint processId)
        {
            processId = OwnerProcessId;
            return processId == 0 ? 0U : 1U;
        }

        public nint GlobalAlloc(uint flags, nuint bytes)
        {
            var memory = Marshal.AllocHGlobal(checked((int)bytes));
            allocations.Add(memory);
            return memory;
        }

        public nint GlobalLock(nint memory)
        {
            GlobalLockCount++;
            return memory;
        }

        public bool GlobalUnlock(nint memory) => true;

        public nuint GlobalSize(nint memory) =>
            memory == clipboardData ? ClipboardDataSizeOverride ?? ClipboardDataSize : 0;

        public nint GlobalFree(nint memory)
        {
            if (allocations.Remove(memory))
            {
                Marshal.FreeHGlobal(memory);
            }

            GlobalFreeCount++;
            return nint.Zero;
        }

        public int GetLastError() => LastError;

        public void Dispose()
        {
            foreach (var allocation in allocations)
            {
                Marshal.FreeHGlobal(allocation);
            }

            allocations.Clear();
        }
    }
}
