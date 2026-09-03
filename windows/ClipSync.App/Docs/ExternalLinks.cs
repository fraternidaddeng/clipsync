using System.ComponentModel;
using System.Diagnostics;
using ClipSync.App.Diagnostics;

namespace ClipSync.App.Docs;

/// <summary>
/// Hands a web address to the user's default browser. The app never opens a browser on its
/// own initiative: this runs only from an explicit click, fetches nothing ahead of time and
/// records only a diagnostics code when no browser could be started.
/// </summary>
public static class ExternalLinks
{
    public static bool TryOpen(Uri url)
    {
        ArgumentNullException.ThrowIfNull(url);
        try
        {
            using var process = Process.Start(new ProcessStartInfo(url.AbsoluteUri) { UseShellExecute = true });
            return true;
        }
        catch (Exception exception) when (exception is Win32Exception or InvalidOperationException)
        {
            LocalDiagnostics.Write($"open_url_failed_{exception.GetType().Name}");
            return false;
        }
    }
}
