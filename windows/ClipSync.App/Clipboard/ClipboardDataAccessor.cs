using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace ClipSync.App.Clipboard;

internal sealed record ClipboardTextSnapshot(string Text, string? SourceProcess, uint SequenceNumber = 0);

internal interface IClipboardDataAccess
{
    ClipboardTextSnapshot? ReadText(nint listenerWindow);

    void WriteText(nint listenerWindow, string text);
}

internal interface IClipboardRetryDelay
{
    void Wait(TimeSpan delay);
}

internal interface IClipboardOwnerResolver
{
    string? ResolveProcess(uint processId);
}

internal interface IClipboardNativeApi
{
    bool OpenClipboard(nint ownerWindow);

    bool CloseClipboard();

    bool EmptyClipboard();

    bool IsClipboardFormatAvailable(uint format);

    nint GetClipboardData(uint format);

    nint SetClipboardData(uint format, nint memory);

    nint GetClipboardOwner();

    uint GetClipboardSequenceNumber();

    uint GetWindowThreadProcessId(nint window, out uint processId);

    nint GlobalAlloc(uint flags, nuint bytes);

    nint GlobalLock(nint memory);

    bool GlobalUnlock(nint memory);

    nuint GlobalSize(nint memory);

    nint GlobalFree(nint memory);

    int GetLastError();
}

internal sealed class ClipboardDataAccessor : IClipboardDataAccess
{
    internal const uint UnicodeTextFormat = 13;
    // The Core policy enforces the 1 MiB UTF-8 limit. This native guard prevents
    // an untrusted HGLOBAL from forcing a multi-gigabyte managed allocation first.
    internal const nuint MaximumUnicodeTextBytes = (2u * 1024u * 1024u) + sizeof(char);
    private const uint MoveableZeroInitializedMemory = 0x0002 | 0x0040;
    private const int DefaultOpenAttempts = 5;
    private static readonly TimeSpan DefaultRetryDelay = TimeSpan.FromMilliseconds(20);

    private readonly IClipboardNativeApi nativeApi;
    private readonly IClipboardOwnerResolver ownerResolver;
    private readonly IClipboardRetryDelay retryDelay;
    private readonly int openAttempts;
    private readonly TimeSpan openRetryDelay;

    internal ClipboardDataAccessor()
        : this(
            Win32ClipboardNativeApi.Instance,
            new ProcessClipboardOwnerResolver(),
            ThreadClipboardRetryDelay.Instance,
            DefaultOpenAttempts,
            DefaultRetryDelay)
    {
    }

    internal ClipboardDataAccessor(
        IClipboardNativeApi nativeApi,
        IClipboardOwnerResolver ownerResolver,
        IClipboardRetryDelay retryDelay,
        int openAttempts = DefaultOpenAttempts,
        TimeSpan? openRetryDelay = null)
    {
        ArgumentNullException.ThrowIfNull(nativeApi);
        ArgumentNullException.ThrowIfNull(ownerResolver);
        ArgumentNullException.ThrowIfNull(retryDelay);
        ArgumentOutOfRangeException.ThrowIfLessThan(openAttempts, 1);

        var delay = openRetryDelay ?? DefaultRetryDelay;
        ArgumentOutOfRangeException.ThrowIfLessThan(delay, TimeSpan.Zero);

        this.nativeApi = nativeApi;
        this.ownerResolver = ownerResolver;
        this.retryDelay = retryDelay;
        this.openAttempts = openAttempts;
        this.openRetryDelay = delay;
    }

    public ClipboardTextSnapshot? ReadText(nint listenerWindow)
    {
        string? text = null;
        var ownerProcessId = ResolveClipboardOwnerProcessId();
        uint sequenceNumber = 0;
        OpenClipboardWithRetry(listenerWindow);
        try
        {
            if (!nativeApi.IsClipboardFormatAvailable(UnicodeTextFormat))
            {
                return null;
            }

            var memory = nativeApi.GetClipboardData(UnicodeTextFormat);
            if (memory == nint.Zero)
            {
                throw NativeFailure("read clipboard data");
            }

            var byteCount = nativeApi.GlobalSize(memory);
            if (byteCount < sizeof(char) || byteCount % sizeof(char) != 0 || byteCount > MaximumUnicodeTextBytes)
            {
                throw new InvalidDataException("The clipboard text allocation has an invalid size.");
            }

            var pointer = nativeApi.GlobalLock(memory);
            if (pointer == nint.Zero)
            {
                throw NativeFailure("lock clipboard data");
            }

            try
            {
                var characterCount = checked((int)(byteCount / sizeof(char)));
                text = Marshal.PtrToStringUni(pointer, characterCount) ?? string.Empty;
                var terminator = text.IndexOf('\0', StringComparison.Ordinal);
                if (terminator < 0)
                {
                    throw new InvalidDataException("The clipboard text is not NUL terminated.");
                }

                text = text[..terminator];
            }
            finally
            {
                _ = nativeApi.GlobalUnlock(memory);
            }

            sequenceNumber = nativeApi.GetClipboardSequenceNumber();
        }
        finally
        {
            _ = nativeApi.CloseClipboard();
        }

        return new ClipboardTextSnapshot(
            text,
            ownerResolver.ResolveProcess(ownerProcessId),
            sequenceNumber);
    }

    private uint ResolveClipboardOwnerProcessId()
    {
        var owner = nativeApi.GetClipboardOwner();
        if (owner == nint.Zero || nativeApi.GetWindowThreadProcessId(owner, out var processId) == 0)
        {
            return 0;
        }

        return processId;
    }

    public void WriteText(nint listenerWindow, string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        if (text.Contains('\0', StringComparison.Ordinal))
        {
            throw new ArgumentException("Clipboard text cannot contain an embedded NUL character.", nameof(text));
        }

        nint memory = nint.Zero;
        var clipboardOpened = false;
        var ownershipTransferred = false;
        try
        {
            var encoded = Encoding.Unicode.GetBytes(text + '\0');
            memory = nativeApi.GlobalAlloc(MoveableZeroInitializedMemory, checked((nuint)encoded.Length));
            if (memory == nint.Zero)
            {
                throw NativeFailure("allocate clipboard data");
            }

            var pointer = nativeApi.GlobalLock(memory);
            if (pointer == nint.Zero)
            {
                throw NativeFailure("lock clipboard data");
            }

            try
            {
                Marshal.Copy(encoded, 0, pointer, encoded.Length);
            }
            finally
            {
                _ = nativeApi.GlobalUnlock(memory);
            }

            OpenClipboardWithRetry(listenerWindow);
            clipboardOpened = true;
            if (!nativeApi.EmptyClipboard())
            {
                throw NativeFailure("empty the clipboard");
            }

            if (nativeApi.SetClipboardData(UnicodeTextFormat, memory) == nint.Zero)
            {
                throw NativeFailure("write clipboard data");
            }

            ownershipTransferred = true;
        }
        finally
        {
            if (memory != nint.Zero && !ownershipTransferred)
            {
                _ = nativeApi.GlobalFree(memory);
            }

            if (clipboardOpened)
            {
                _ = nativeApi.CloseClipboard();
            }
        }
    }

    private void OpenClipboardWithRetry(nint listenerWindow)
    {
        var error = 0;
        for (var attempt = 1; attempt <= openAttempts; attempt++)
        {
            if (nativeApi.OpenClipboard(listenerWindow))
            {
                return;
            }

            error = nativeApi.GetLastError();
            if (attempt < openAttempts)
            {
                retryDelay.Wait(openRetryDelay);
            }
        }

        throw new Win32Exception(error, $"Unable to open the Windows clipboard after {openAttempts} attempts.");
    }

    private Win32Exception NativeFailure(string operation)
    {
        var error = nativeApi.GetLastError();
        return new Win32Exception(error == 0 ? 1 : error, $"Unable to {operation}.");
    }
}

internal sealed class ProcessClipboardOwnerResolver : IClipboardOwnerResolver
{
    public string? ResolveProcess(uint processId)
    {
        if (processId == 0)
        {
            return null;
        }

        try
        {
            using var process = Process.GetProcessById(checked((int)processId));
            return process.ProcessName;
        }
        catch (Exception exception) when (
            exception is ArgumentException or InvalidOperationException or Win32Exception or NotSupportedException or OverflowException)
        {
            return null;
        }
    }
}

internal sealed class ThreadClipboardRetryDelay : IClipboardRetryDelay
{
    internal static ThreadClipboardRetryDelay Instance { get; } = new();

    private ThreadClipboardRetryDelay()
    {
    }

    public void Wait(TimeSpan delay) => Thread.Sleep(delay);
}

internal sealed class Win32ClipboardNativeApi : IClipboardNativeApi
{
    internal static Win32ClipboardNativeApi Instance { get; } = new();

    private Win32ClipboardNativeApi()
    {
    }

    public bool OpenClipboard(nint ownerWindow) => NativeMethods.OpenClipboard(ownerWindow);

    public bool CloseClipboard() => NativeMethods.CloseClipboard();

    public bool EmptyClipboard() => NativeMethods.EmptyClipboard();

    public bool IsClipboardFormatAvailable(uint format) => NativeMethods.IsClipboardFormatAvailable(format);

    public nint GetClipboardData(uint format) => NativeMethods.GetClipboardData(format);

    public nint SetClipboardData(uint format, nint memory) => NativeMethods.SetClipboardData(format, memory);

    public nint GetClipboardOwner() => NativeMethods.GetClipboardOwner();

    public uint GetClipboardSequenceNumber() => NativeMethods.GetClipboardSequenceNumber();

    public uint GetWindowThreadProcessId(nint window, out uint processId) =>
        NativeMethods.GetWindowThreadProcessId(window, out processId);

    public nint GlobalAlloc(uint flags, nuint bytes) => NativeMethods.GlobalAlloc(flags, bytes);

    public nint GlobalLock(nint memory) => NativeMethods.GlobalLock(memory);

    public bool GlobalUnlock(nint memory) => NativeMethods.GlobalUnlock(memory);

    public nuint GlobalSize(nint memory) => NativeMethods.GlobalSize(memory);

    public nint GlobalFree(nint memory) => NativeMethods.GlobalFree(memory);

    public int GetLastError() => Marshal.GetLastWin32Error();

    private static class NativeMethods
    {
        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool OpenClipboard(nint newOwner);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CloseClipboard();

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool EmptyClipboard();

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool IsClipboardFormatAvailable(uint format);

        [DllImport("user32.dll", SetLastError = true)]
        internal static extern nint GetClipboardData(uint format);

        [DllImport("user32.dll", SetLastError = true)]
        internal static extern nint SetClipboardData(uint format, nint memory);

        [DllImport("user32.dll")]
        internal static extern nint GetClipboardOwner();

        [DllImport("user32.dll")]
        internal static extern uint GetClipboardSequenceNumber();

        [DllImport("user32.dll", SetLastError = true)]
        internal static extern uint GetWindowThreadProcessId(nint window, out uint processId);

        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern nint GlobalAlloc(uint flags, nuint bytes);

        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern nint GlobalLock(nint memory);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool GlobalUnlock(nint memory);

        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern nuint GlobalSize(nint memory);

        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern nint GlobalFree(nint memory);
    }
}
