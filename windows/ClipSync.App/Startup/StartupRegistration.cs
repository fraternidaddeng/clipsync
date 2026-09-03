using Microsoft.Win32;
using System.IO;
using System.Security;

namespace ClipSync.App.Startup;

/// <summary>
/// What the per-user Run entry really says right now, independent of the stored intent.
/// The 偏好 row states this next to the toggle so "开了但没生效" is visible.
/// </summary>
public enum StartupRegistrationState
{
    /// <summary>No Run value under this name.</summary>
    NotRegistered,

    /// <summary>The Run value exists and Windows will honour it at the next sign-in.</summary>
    Registered,

    /// <summary>
    /// The Run value exists but the user switched it off in Task Manager › Startup apps
    /// (Explorer's StartupApproved flags); Windows will not launch it.
    /// </summary>
    DisabledInTaskManager,

    /// <summary>The registry could not be read; nothing is claimed either way.</summary>
    Unknown,
}

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

    /// <summary>Explorer's per-entry enable/disable flags behind Task Manager › Startup apps.</summary>
    private const string StartupApprovedKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";

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

    /// <summary>
    /// Reads the Run value and Explorer's StartupApproved flag for it. Read-only, standard
    /// user rights; a registry read failure is reported as <see cref="StartupRegistrationState.Unknown"/>
    /// rather than guessed.
    /// </summary>
    public static StartupRegistrationState Probe(string valueName = DefaultValueName)
    {
        try
        {
            if (!IsEnabled(valueName))
            {
                return StartupRegistrationState.NotRegistered;
            }

            using var approved = Registry.CurrentUser.OpenSubKey(StartupApprovedKeyPath);
            return IsDisabledByStartupApproval(approved?.GetValue(valueName) as byte[])
                ? StartupRegistrationState.DisabledInTaskManager
                : StartupRegistrationState.Registered;
        }
        catch (Exception exception) when (exception is SecurityException
            or UnauthorizedAccessException
            or IOException)
        {
            return StartupRegistrationState.Unknown;
        }
    }

    /// <summary>
    /// Explorer stores a 12-byte flag per entry: the first byte is 0x02/0x06 when enabled and
    /// 0x03/0x07 when the user disabled it in Task Manager — the low bit carries the answer.
    /// A missing flag means Task Manager has never touched the entry, so it is enabled.
    /// </summary>
    internal static bool IsDisabledByStartupApproval(byte[]? flags) =>
        flags is { Length: > 0 } && (flags[0] & 0x01) == 0x01;
}
