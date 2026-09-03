using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
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
/// Launches Windows' own <c>netsh.exe</c> (full System32 path — never PATH-resolved) through
/// ShellExecute with the <c>runas</c> verb, so the system UAC dialog asks for consent (ADR 0006
/// §2). Arguments are the command's constant tokens, passed one per <c>ArgumentList</c> entry.
/// The console window stays hidden; the exit code decides the outcome, and a declined UAC
/// (Win32 error 1223) is reported as a cancellation, not as success and not as an error.
/// </summary>
public sealed class FirewallRuleElevator : IFirewallRuleElevator
{
    private const int ErrorCancelled = 1223;

    public async Task<ElevationOutcome> RunAsync(FirewallRuleCommand command, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(command);
        var verb = command.IsRemoval ? "remove" : "add";
        var startInfo = new ProcessStartInfo
        {
            FileName = Path.Combine(Environment.SystemDirectory, "netsh.exe"),
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        foreach (var argument in command.Arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        ElevationOutcome outcome;
        try
        {
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                outcome = new ElevationOutcome(ElevationStatus.Failed, FailureType: "NoProcess");
            }
            else
            {
                await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
                outcome = process.ExitCode == 0
                    ? ElevationOutcome.AppliedOutcome
                    : new ElevationOutcome(ElevationStatus.Failed, process.ExitCode);
            }
        }
        catch (Win32Exception exception) when (exception.NativeErrorCode == ErrorCancelled)
        {
            outcome = ElevationOutcome.CancelledOutcome;
        }
        catch (Win32Exception exception)
        {
            outcome = new ElevationOutcome(ElevationStatus.Failed, FailureType: $"{exception.GetType().Name}_{exception.NativeErrorCode}");
        }
        catch (InvalidOperationException exception)
        {
            outcome = new ElevationOutcome(ElevationStatus.Failed, FailureType: exception.GetType().Name);
        }

        LocalDiagnostics.Write(outcome.Status switch
        {
            ElevationStatus.Applied => $"firewall_rule_{verb}_applied",
            ElevationStatus.Cancelled => $"firewall_rule_{verb}_cancelled",
            _ => $"firewall_rule_{verb}_failed_{outcome.ExitCode?.ToString(CultureInfo.InvariantCulture) ?? outcome.FailureType}",
        });
        return outcome;
    }
}
