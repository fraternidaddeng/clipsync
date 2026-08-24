using ClipSync.Peer.Diagnostics;

namespace ClipSync.App.Diagnostics;

internal static class LocalDiagnostics
{
    private static readonly object Sync = new();

    /// <summary>
    /// In-memory ring buffer that always keeps the most recent codes, whether or not the optional
    /// file sink is configured. It backs the tray-launched diagnostics viewer.
    /// </summary>
    private static readonly BoundedDiagnosticsLog Buffer = new();

    public static void Write(string code)
    {
        var now = DateTimeOffset.UtcNow;
        Buffer.Record(code, now);

        var path = Environment.GetEnvironmentVariable("CLIPSYNC_DIAGNOSTICS_PATH");
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        lock (Sync)
        {
            System.IO.File.AppendAllText(path, $"{now:O} {code}{Environment.NewLine}");
        }
    }

    /// <summary>Recent diagnostic codes, newest first (codes and timestamps only).</summary>
    public static IReadOnlyList<DiagnosticEntry> Snapshot() => Buffer.Snapshot();
}
