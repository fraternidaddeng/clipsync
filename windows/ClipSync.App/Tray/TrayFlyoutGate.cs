namespace ClipSync.App.Tray;

/// <summary>
/// Decides whether the tray flyout may be shown. WPF offers no public signal during the
/// window between <c>Application.Shutdown()</c> and the end of <c>OnExit</c>
/// (<c>Dispatcher.HasShutdownStarted</c> flips only afterwards), so the application tracks
/// its own exit intent and passes both facts in here.
/// </summary>
internal static class TrayFlyoutGate
{
    public static bool CanShowFlyout(bool isExiting, bool dispatcherShutdownStarted) =>
        !isExiting && !dispatcherShutdownStarted;
}
