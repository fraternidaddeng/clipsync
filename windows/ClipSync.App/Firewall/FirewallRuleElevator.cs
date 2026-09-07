using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using ClipSync.App.Diagnostics;

namespace ClipSync.App.Firewall;

/// <summary>What happened to one elevated netsh run.</summary>
public enum ElevationStatus
{
    /// <summary>netsh exited 0: the rule was added or deleted.</summary>
    Applied,

    /// <summary>The user declined the UAC prompt (ERROR_CANCELLED); nothing changed.</summary>
    Cancelled,

    /// <summary>netsh ran but exited non-zero, or could not be started at all.</summary>
    Failed,
}

/// <summary>
/// The outcome of an elevated run. <see cref="ExitCode"/> is netsh's exit code for
/// <see cref="ElevationStatus.Failed"/> after a completed run, null when the launch itself failed;
/// <see cref="FailureType"/> then names the exception type (no message — nothing user-derived leaks).
/// </summary>
public sealed record ElevationOutcome(ElevationStatus Status, int? ExitCode = null, string? FailureType = null)
{
    public static readonly ElevationOutcome AppliedOutcome = new(ElevationStatus.Applied, 0);
    public static readonly ElevationOutcome CancelledOutcome = new(ElevationStatus.Cancelled);
}

/// <summary>Runs one <see cref="FirewallRuleCommand"/> with elevation; the interface keeps the view model UAC-free in tests.</summary>
public interface IFirewallRuleElevator
{
    Task<ElevationOutcome> RunAsync(FirewallRuleCommand command, CancellationToken cancellationToken);
}

/// <summary>
/// How an elevated process is started and waited for — the one seam between
/// <see cref="FirewallRuleElevator"/> and ShellExecute, so tests can see the exact file name and
/// argv without a UAC prompt. Throws what <c>Process.Start</c> throws.
/// </summary>
internal interface IElevatedProcessLauncher
{
    /// <returns>The exit code, or null when no process was started.</returns>
    Task<int?> RunAsync(string fileName, IReadOnlyList<string> arguments, CancellationToken cancellationToken);
}

/// <summary>
/// Launches Windows' own <c>netsh.exe</c> (full System32 path — never PATH-resolved) through
/// ShellExecute with the <c>runas</c> verb, so the system UAC dialog asks for consent (ADR 0006
/// §2). A single-step command's constant tokens go one per <c>ArgumentList</c> entry; a
/// multi-step 放行 (Block-rule cleanup + add) is written to a temporary script file and netsh runs
/// it with <c>-f</c>, so the whole sequence needs one consent. The file is UTF-8 without a BOM —
/// the only encoding netsh's script reader accepts for non-ASCII names — and is deleted as soon
/// as netsh exits, whatever the outcome. The console window stays hidden; the exit code decides
/// the outcome (netsh -f exits non-zero when any line failed, having still run the rest), and a
/// declined UAC (Win32 error 1223) is reported as a cancellation, not as success and not as an error.
/// </summary>
public sealed class FirewallRuleElevator : IFirewallRuleElevator
{
    private const int ErrorCancelled = 1223;

    private static readonly UTF8Encoding ScriptEncoding = new(encoderShouldEmitUTF8Identifier: false);

    private readonly IElevatedProcessLauncher launcher;

    public FirewallRuleElevator()
        : this(new ShellExecuteRunasLauncher())
    {
    }

    internal FirewallRuleElevator(IElevatedProcessLauncher launcher)
    {
        this.launcher = launcher;
    }

    internal static string NetshPath => Path.Combine(Environment.SystemDirectory, "netsh.exe");

    public async Task<ElevationOutcome> RunAsync(FirewallRuleCommand command, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(command);
        var verb = command.IsRemoval
            ? "remove"
            : command.RequiresScript ? "add_with_block_cleanup" : "add";

        string? scriptPath = null;
        ElevationOutcome outcome;
        try
        {
            IReadOnlyList<string> arguments;
            if (command.RequiresScript)
            {
                scriptPath = Path.Combine(Path.GetTempPath(), $"clipsync-firewall-{Guid.NewGuid():N}.txt");
                await File.WriteAllTextAsync(scriptPath, command.ToScriptText(), ScriptEncoding, cancellationToken).ConfigureAwait(false);
                arguments = ["-f", scriptPath];
            }
            else
            {
                arguments = command.Arguments;
            }

            var exitCode = await launcher.RunAsync(NetshPath, arguments, cancellationToken).ConfigureAwait(false);
            outcome = exitCode switch
            {
                null => new ElevationOutcome(ElevationStatus.Failed, FailureType: "NoProcess"),
                0 => ElevationOutcome.AppliedOutcome,
                _ => new ElevationOutcome(ElevationStatus.Failed, exitCode),
            };
        }
        catch (Win32Exception exception) when (exception.NativeErrorCode == ErrorCancelled)
        {
            outcome = ElevationOutcome.CancelledOutcome;
        }
        catch (Win32Exception exception)
        {
            outcome = new ElevationOutcome(ElevationStatus.Failed, FailureType: $"{exception.GetType().Name}_{exception.NativeErrorCode}");
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException or UnauthorizedAccessException)
        {
            outcome = new ElevationOutcome(ElevationStatus.Failed, FailureType: exception.GetType().Name);
        }
        finally
        {
            if (scriptPath is not null)
            {
                try
                {
                    File.Delete(scriptPath);
                }
                catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
                {
                    LocalDiagnostics.Write($"firewall_rule_script_cleanup_failed_{exception.GetType().Name}");
                }
            }
        }

        LocalDiagnostics.Write(outcome.Status switch
        {
            ElevationStatus.Applied => $"firewall_rule_{verb}_applied",
            ElevationStatus.Cancelled => $"firewall_rule_{verb}_cancelled",
            _ => $"firewall_rule_{verb}_failed_{outcome.ExitCode?.ToString(CultureInfo.InvariantCulture) ?? outcome.FailureType}",
        });
        return outcome;
    }

    private sealed class ShellExecuteRunasLauncher : IElevatedProcessLauncher
    {
        public async Task<int?> RunAsync(string fileName, IReadOnlyList<string> arguments, CancellationToken cancellationToken)
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = fileName,
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            foreach (var argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }

            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return null;
            }

            await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
            return process.ExitCode;
        }
    }
}
