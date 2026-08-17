using System.ComponentModel;
using System.Runtime.ExceptionServices;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Threading;

namespace ClipSync.App.Clipboard;

internal interface IClipboardMessageWindow : IDisposable
{
    event EventHandler? ClipboardUpdated;

    nint Handle { get; }

    void Start();

    void Stop();
}

internal interface IClipboardListenerNativeApi
{
    bool AddClipboardFormatListener(nint window);

    bool RemoveClipboardFormatListener(nint window);

    int GetLastError();
}

internal sealed class MessageOnlyClipboardWindow : IClipboardMessageWindow
{
    internal const int ClipboardUpdateMessage = 0x031D;
    private static readonly TimeSpan ThreadStartupTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan ThreadShutdownTimeout = TimeSpan.FromSeconds(10);

    private readonly object lifecycleLock = new();
    private readonly IClipboardListenerNativeApi nativeApi;
    private HwndSource? source;
    private Dispatcher? listenerDispatcher;
    private Thread? listenerThread;
    private nint handle;
    private int disposed;

    internal MessageOnlyClipboardWindow()
        : this(Win32ClipboardListenerNativeApi.Instance)
    {
    }

    internal MessageOnlyClipboardWindow(IClipboardListenerNativeApi nativeApi)
    {
        ArgumentNullException.ThrowIfNull(nativeApi);
        this.nativeApi = nativeApi;
    }

    public event EventHandler? ClipboardUpdated;

    public nint Handle => Volatile.Read(ref handle);

    public void Start()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref disposed) != 0, this);
        lock (lifecycleLock)
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref disposed) != 0, this);
            if (listenerThread is not null)
            {
                return;
            }

            using var startupCompleted = new ManualResetEventSlim();
            ExceptionDispatchInfo? startupFailure = null;
            var thread = new Thread(() => RunMessageLoop(startupCompleted, failure => startupFailure = failure))
            {
                IsBackground = true,
                Name = "ClipSync clipboard listener"
            };
            thread.SetApartmentState(ApartmentState.STA);
            listenerThread = thread;
            thread.Start();

            if (!startupCompleted.Wait(ThreadStartupTimeout))
            {
                RequestShutdown();
                listenerThread = null;
                throw new TimeoutException("The clipboard listener thread did not start in time.");
            }

            if (startupFailure is not null)
            {
                thread.Join(ThreadShutdownTimeout);
                listenerThread = null;
                startupFailure.Throw();
            }

            lock (lifecycleLock)
            {
                if (Volatile.Read(ref disposed) != 0)
                {
                    RequestShutdown();
                    listenerThread = null;
                    throw new ObjectDisposedException(nameof(MessageOnlyClipboardWindow));
                }
            }
        }
    }

    public void Stop()
    {
        lock (lifecycleLock)
        {
            var thread = listenerThread;
            if (thread is null)
            {
                return;
            }

            RequestShutdown();
            if (thread.ManagedThreadId != Environment.CurrentManagedThreadId &&
                !thread.Join(ThreadShutdownTimeout))
            {
                throw new TimeoutException("The clipboard listener thread did not stop in time.");
            }

            listenerThread = null;
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
        {
            return;
        }

        Stop();
    }

    private void RunMessageLoop(
        ManualResetEventSlim startupCompleted,
        Action<ExceptionDispatchInfo> reportStartupFailure)
    {
        var started = false;
        try
        {
            listenerDispatcher = Dispatcher.CurrentDispatcher;
            StartCore();
            started = true;
        }
        catch (Exception exception)
        {
            reportStartupFailure(ExceptionDispatchInfo.Capture(exception));
        }
        finally
        {
            startupCompleted.Set();
        }

        if (!started)
        {
            listenerDispatcher = null;
            return;
        }

        try
        {
            Dispatcher.Run();
        }
        finally
        {
            StopCore();
            listenerDispatcher = null;
        }
    }

    private void StartCore()
    {
        // WM_CLIPBOARDUPDATE is delivered through the broadcast path. Windows
        // excludes HWND_MESSAGE parents from that path, so this is a zero-sized
        // top-level popup kept out of activation and the taskbar instead.
        var parameters = new HwndSourceParameters("ClipSync.Clipboard.Listener")
        {
            WindowStyle = unchecked((int)0x80000000), // WS_POPUP
            ExtendedWindowStyle = 0x08000080, // WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW
            Width = 0,
            Height = 0
        };

        var newSource = new HwndSource(parameters);
        newSource.AddHook(WindowProcedure);
        var newHandle = newSource.Handle;
        if (!nativeApi.AddClipboardFormatListener(newHandle))
        {
            var error = nativeApi.GetLastError();
            newSource.RemoveHook(WindowProcedure);
            newSource.Dispose();
            throw new Win32Exception(error, "Unable to register the clipboard format listener.");
        }

        source = newSource;
        Volatile.Write(ref handle, newHandle);
    }

    private void StopCore()
    {
        var currentSource = source;
        if (currentSource is null)
        {
            return;
        }

        source = null;
        var currentHandle = Interlocked.Exchange(ref handle, nint.Zero);
        _ = nativeApi.RemoveClipboardFormatListener(currentHandle);
        currentSource.RemoveHook(WindowProcedure);
        currentSource.Dispose();
    }

    private void RequestShutdown()
    {
        var dispatcher = listenerDispatcher;
        if (dispatcher is null || dispatcher.HasShutdownStarted || dispatcher.HasShutdownFinished)
        {
            return;
        }

        if (dispatcher.CheckAccess())
        {
            StopCore();
            dispatcher.BeginInvokeShutdown(DispatcherPriority.Send);
            return;
        }

        try
        {
            dispatcher.BeginInvoke(
                DispatcherPriority.Send,
                new Action(() =>
                {
                    StopCore();
                    dispatcher.BeginInvokeShutdown(DispatcherPriority.Send);
                }));
        }
        catch (InvalidOperationException)
        {
            // Dispatcher shutdown won the race; the listener thread cleans up in finally.
        }
    }

    private nint WindowProcedure(nint window, int message, nint wordParameter, nint longParameter, ref bool handled)
    {
        if (message == ClipboardUpdateMessage)
        {
            handled = true;
            ClipboardUpdated?.Invoke(this, EventArgs.Empty);
        }

        return nint.Zero;
    }
}

internal sealed class Win32ClipboardListenerNativeApi : IClipboardListenerNativeApi
{
    internal static Win32ClipboardListenerNativeApi Instance { get; } = new();

    private Win32ClipboardListenerNativeApi()
    {
    }

    public bool AddClipboardFormatListener(nint window) => NativeMethods.AddClipboardFormatListener(window);

    public bool RemoveClipboardFormatListener(nint window) => NativeMethods.RemoveClipboardFormatListener(window);

    public int GetLastError() => Marshal.GetLastWin32Error();

    private static class NativeMethods
    {
        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool AddClipboardFormatListener(nint window);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool RemoveClipboardFormatListener(nint window);

    }
}
