using System.Runtime.InteropServices;
using System.Windows.Interop;

namespace ClipSync.App.Tray;

/// <summary>
/// Owns the global 呼出浮窗 hotkey: a hidden zero-sized window on the UI thread receives
/// WM_HOTKEY and raises <see cref="Pressed"/>. Registration failure (typically another
/// program holding the chord) is reported through <see cref="TryApply"/>'s return value
/// so the preferences row can state the fact honestly instead of pretending it worked.
/// </summary>
internal sealed class FlyoutHotkeyManager : IDisposable
{
    private const int HotkeyId = 0x0C51;
    private const int HotkeyMessage = 0x0312;

    private HwndSource? source;
    private bool registered;
    private bool disposed;

    public event Action? Pressed;

    /// <summary>
    /// Reconciles the registration with a gesture string: empty turns the hotkey off
    /// (returns true), a valid gesture registers it. Returns false when the gesture is
    /// unparsable or RegisterHotKey refuses it (already taken by another program).
    /// </summary>
    public bool TryApply(string? gesture)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        Unregister();
        if (string.IsNullOrWhiteSpace(gesture))
        {
            return true;
        }

        if (!HotkeyGesture.TryParse(gesture, out var modifiers, out var virtualKey))
        {
            return false;
        }

        var window = EnsureWindow();
        registered = NativeMethods.RegisterHotKey(
            window.Handle,
            HotkeyId,
            modifiers | HotkeyGesture.ModifierNoRepeat,
            virtualKey);
        return registered;
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        Unregister();
        if (source is not null)
        {
            source.RemoveHook(WindowProcedure);
            source.Dispose();
            source = null;
        }
    }

    private HwndSource EnsureWindow()
    {
        if (source is not null)
        {
            return source;
        }

        // Same shape as the clipboard listener window: WM_HOTKEY needs a real top-level
        // window, so this is a zero-sized popup kept out of activation and the taskbar.
        var parameters = new HwndSourceParameters("ClipSync.Tray.Hotkey")
        {
            WindowStyle = unchecked((int)0x80000000), // WS_POPUP
            ExtendedWindowStyle = 0x08000080, // WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW
            Width = 0,
            Height = 0
        };
        source = new HwndSource(parameters);
        source.AddHook(WindowProcedure);
        return source;
    }

    private void Unregister()
    {
        if (!registered || source is null)
        {
            return;
        }

        _ = NativeMethods.UnregisterHotKey(source.Handle, HotkeyId);
        registered = false;
    }

    private nint WindowProcedure(nint window, int message, nint wordParameter, nint longParameter, ref bool handled)
    {
        if (message == HotkeyMessage && wordParameter == HotkeyId)
        {
            handled = true;
            Pressed?.Invoke();
        }

        return nint.Zero;
    }

    private static class NativeMethods
    {
        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool RegisterHotKey(nint window, int id, uint modifiers, uint virtualKey);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool UnregisterHotKey(nint window, int id);
    }
}
