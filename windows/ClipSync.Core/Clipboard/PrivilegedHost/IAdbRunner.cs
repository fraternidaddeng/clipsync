namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>One completed adb invocation. Output is captured, never streamed to a console.</summary>
public sealed record AdbCommandResult(int ExitCode, string StandardOutput, string StandardError);

/// <summary>
/// Runs the adb executable. The Core layer depends only on this seam so the assistant's
/// orchestration is unit-testable with a fake; the real, process-launching implementation
/// lives in the Windows app layer.
/// </summary>
public interface IAdbRunner
{
    /// <summary>
    /// True when an adb executable was located. When false the assistant reports the
    /// "adb 未找到" state instead of attempting to launch anything.
    /// </summary>
    bool IsAvailable { get; }

    /// <summary>
    /// A short, human description of where adb was found (a path) or why it was not — shown as a
    /// fact on the card. Never null; empty only before the first locate attempt.
    /// </summary>
    string LocationDescription { get; }

    /// <summary>
    /// Runs <c>adb</c> with the given arguments (already tokenized; no shell involved) and
    /// returns its captured result. Implementations must not throw for a non-zero adb exit —
    /// that is a normal result the caller interprets. <paramref name="timeout"/> overrides the
    /// implementation's default per-command timeout; callers set it only for commands that are
    /// legitimately slow (the on-device start script), never to hide a wedged transport.
    /// </summary>
    Task<AdbCommandResult> RunAsync(
        IReadOnlyList<string> arguments,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default);
}
