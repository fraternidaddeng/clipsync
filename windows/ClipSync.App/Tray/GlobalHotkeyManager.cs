using System.Runtime.InteropServices;
using System.Windows.Interop;

namespace ClipSync.App.Tray;

/// <summary>
/// The two global hotkeys owned by the app (settings-roadmap P1-9): summon the tray
/// flyout, and pause/resume sync. Values double as the Win32 RegisterHotKey ids.
/// </summary>
internal enum GlobalHotkey
{
    Flyout = 0x0C51,
    PauseSync = 0x0C52,
}

/// <summary>
/// Owns the global hotkeys（呼出浮窗 / 暂停同步）: a single hidden zero-sized window on
/// the UI thread receives WM_HOTKEY for every registered id and raises
/// <see cref="Pressed"/> with the matching <see cref="GlobalHotkey"/>. Registration
/// failure (typically another program holding the chord) is reported through
/// <see cref="TryApply"/>'s return value so each preferences row can state the fact
/// honestly instead of pretending it worked.
/// </summary>
internal sealed class GlobalHotkeyManager : IDisposable
{
    private const int HotkeyMessage = 0x0312;

    private readonly HashSet<GlobalHotkey> registered = [];
    private HwndSource? source;
    private bool disposed;

    public event Action<GlobalHotkey>? Pressed;

    /// <summary>
    /// Reconciles one hotkey's registration with a gesture string: empty turns it off
    /// (returns true), a valid gesture registers it. Returns false when the gesture is
    /// unparsable or RegisterHotKey refuses it (already taken by another program).
    /// </summary>
    public bool TryApply(GlobalHotkey hotkey, string? gesture)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        Unregister(hotkey);
        if (string.IsNullOrWhiteSpace(gesture))
        {
            return true;
        }

        if (!HotkeyGesture.TryParse(gesture, out var modifiers, out var virtualKey))
        {
            return false;
        }

        var window = EnsureWindow();
        if (!NativeMethods.RegisterHotKey(
            window.Handle,
            (int)hotkey,
            modifiers | HotkeyGesture.ModifierNoRepeat,
            virtualKey))
        {
            return false;
        }

        registered.Add(hotkey);
        return true;
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        foreach (var hotkey in registered.ToArray())
        {
            Unregister(hotkey);
        }

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

    private void Unregister(GlobalHotkey hotkey)
    {
        if (!registered.Remove(hotkey) || source is null)
        {
            return;
        }

        _ = NativeMethods.UnregisterHotKey(source.Handle, (int)hotkey);
    }

    private nint WindowProcedure(nint window, int message, nint wordParameter, nint longParameter, ref bool handled)
    {
        var id = (int)wordParameter;
        if (message == HotkeyMessage
            && id is (int)GlobalHotkey.Flyout or (int)GlobalHotkey.PauseSync)
        {
            handled = true;
            Pressed?.Invoke((GlobalHotkey)id);
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
