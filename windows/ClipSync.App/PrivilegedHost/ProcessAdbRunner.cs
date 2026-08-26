using System.Diagnostics;
using ClipSync.App.Localization;
using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.App.PrivilegedHost;

/// <summary>
/// The real <see cref="IAdbRunner"/>: launches the located adb executable with captured
/// output and no window. Arguments are passed as discrete tokens through
/// <see cref="ProcessStartInfo.ArgumentList"/> so no PC-side shell parses them. A non-zero
/// adb exit is returned as data, never thrown — the assistant interprets it.
/// </summary>
public sealed class ProcessAdbRunner : IAdbRunner
{
    /// <summary>adb calls are quick, but a wedged transport must not hang the UI thread's awaiter.</summary>
    private static readonly TimeSpan CommandTimeout = TimeSpan.FromSeconds(30);

    private readonly string? adbPath;

    public ProcessAdbRunner()
        : this(AdbLocator.Locate())
    {
    }

    public ProcessAdbRunner(string? adbPath) => this.adbPath = adbPath;

    public bool IsAvailable => !string.IsNullOrWhiteSpace(adbPath);

    public string LocationDescription => IsAvailable
        ? Strings.Format(nameof(Strings.Privileged_AdbFoundAtFormat), adbPath!)
        : Strings.Privileged_AdbNotFound;

    public async Task<AdbCommandResult> RunAsync(
        IReadOnlyList<string> arguments,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(arguments);
        if (!IsAvailable)
        {
            return new AdbCommandResult(-1, string.Empty, Strings.Privileged_AdbNotFound);
        }

        var startInfo = new ProcessStartInfo
        {
            FileName = adbPath!,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        using var process = new Process { StartInfo = startInfo };
        try
        {
            if (!process.Start())
            {
                return new AdbCommandResult(-1, string.Empty, Strings.Privileged_AdbNotFound);
            }
        }
        catch (Exception exception) when (exception is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            return new AdbCommandResult(-1, string.Empty, exception.Message);
        }

        var stdoutTask = process.StandardOutput.ReadToEndAsync(cancellationToken);
        var stderrTask = process.StandardError.ReadToEndAsync(cancellationToken);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(CommandTimeout);
        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            TryKill(process);
            return new AdbCommandResult(-1, string.Empty, Strings.Privileged_AdbTimedOut);
        }

        var stdout = await stdoutTask.ConfigureAwait(false);
        var stderr = await stderrTask.ConfigureAwait(false);
        return new AdbCommandResult(process.ExitCode, stdout, stderr);
    }

    private static void TryKill(Process process)
    {
        try
        {
            process.Kill(entireProcessTree: true);
        }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception or NotSupportedException)
        {
            // The process already exited or cannot be killed; nothing more to do.
        }
    }
}
