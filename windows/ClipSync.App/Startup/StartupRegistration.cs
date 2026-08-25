using Microsoft.Win32;

namespace ClipSync.App.Startup;

/// <summary>
/// 运行 · 开机自启（settings-roadmap P0-3）。The settings-table key
/// <c>launch_at_startup</c> mirrors the intent; the mechanism is a per-user
/// HKCU Run value pointing at the executable with <c>--minimized</c>, so an
/// autostarted instance lands silently in the tray instead of popping the main
/// window. No scheduled task, no elevation, no HKLM.
/// </summary>
public static class StartupRegistration
{
    public const string MinimizedArgument = "--minimized";

    /// <summary>Stable ASCII value name; renaming it after release would orphan old entries.</summary>
    public const string DefaultValueName = "ClipSync";

    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

    /// <summary>The Run-value command line: quoted executable plus the silent-start argument.</summary>
    public static string BuildCommand(string executablePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(executablePath);
        return $"\"{executablePath}\" {MinimizedArgument}";
    }

    public static bool IsMinimizedLaunch(IEnumerable<string> arguments)
    {
        ArgumentNullException.ThrowIfNull(arguments);
        return arguments.Any(argument => string.Equals(argument, MinimizedArgument, StringComparison.Ordinal));
    }

    /// <summary>
    /// Writes or deletes the Run value. Re-asserting an enabled entry is intentional:
    /// it refreshes the stored path when the executable has moved since the last run.
    /// </summary>
    public static void SetEnabled(bool enabled, string? executablePath = null, string valueName = DefaultValueName)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath, writable: true)
            ?? throw new InvalidOperationException("Unable to open the per-user Run key.");
        if (!enabled)
        {
            key.DeleteValue(valueName, throwOnMissingValue: false);
            return;
        }

        var path = executablePath
            ?? Environment.ProcessPath
            ?? throw new InvalidOperationException("The executable path for the Run entry could not be determined.");
        key.SetValue(valueName, BuildCommand(path));
    }

    public static bool IsEnabled(string valueName = DefaultValueName)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
        return key?.GetValue(valueName) is string;
    }
}
