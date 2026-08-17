namespace ClipSync.App.Diagnostics;

internal static class LocalDiagnostics
{
    private static readonly object Sync = new();

    public static void Write(string code)
    {
        var path = Environment.GetEnvironmentVariable("CLIPSYNC_DIAGNOSTICS_PATH");
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        lock (Sync)
        {
            System.IO.File.AppendAllText(path, $"{DateTimeOffset.UtcNow:O} {code}{Environment.NewLine}");
        }
    }
}
