using System.Runtime.InteropServices;

namespace ClipSync.App.Diagnostics;

/// <summary>
/// The app is a WinExe tray application and must never show a console window. That holds
/// when the apphost (ClipSync.App.exe) is launched, but not when a console-subsystem
/// launcher hosts the process — most commonly <c>dotnet ClipSync.App.dll</c>, where the
/// dotnet muxer runs the WPF app inside its own console process. Windows then creates an
/// empty console window (titled with the dotnet.exe path) that stays on screen for the
/// whole session. Detaching at startup closes that window when this process is its only
/// owner, and is harmless when the console is an interactive shell shared with a parent.
/// </summary>
internal static class ConsoleWindowGuard
{
    /// <summary>Seam for the two kernel32 calls, so the decision logic is unit-testable.</summary>
    internal interface INativeConsole
    {
        nint GetConsoleWindow();

        bool FreeConsole();
    }

    /// <summary>Detaches the console inherited from a console-subsystem launcher, if any.</summary>
    /// <returns><c>true</c> when a console was attached and successfully detached.</returns>
    public static bool DetachInheritedConsole() => DetachInheritedConsole(Win32NativeConsole.Instance);

    internal static bool DetachInheritedConsole(INativeConsole nativeConsole)
    {
        // The normal WinExe launch has no console at all; leave everything untouched.
        if (nativeConsole.GetConsoleWindow() == nint.Zero)
        {
            return false;
        }

        return nativeConsole.FreeConsole();
    }

    private sealed class Win32NativeConsole : INativeConsole
    {
        internal static Win32NativeConsole Instance { get; } = new();

        private Win32NativeConsole()
        {
        }

        public nint GetConsoleWindow() => NativeMethods.GetConsoleWindow();

        public bool FreeConsole() => NativeMethods.FreeConsole();

        private static class NativeMethods
        {
            [DllImport("kernel32.dll")]
            internal static extern nint GetConsoleWindow();

            [DllImport("kernel32.dll", SetLastError = true)]
            [return: MarshalAs(UnmanagedType.Bool)]
            internal static extern bool FreeConsole();
        }
    }
}
