using ClipSync.App.Tray;

namespace ClipSync.App.Tests.Tray;

public class TrayFlyoutGateTests
{
    [Theory]
    [InlineData(false, false, true)]
    [InlineData(false, true, false)]
    [InlineData(true, false, false)]
    [InlineData(true, true, false)]
    public void FlyoutShowsOnlyWhileNeitherExitSignalIsSet(bool isExiting, bool dispatcherShutdownStarted, bool expected) =>
        Assert.Equal(expected, TrayFlyoutGate.CanShowFlyout(isExiting, dispatcherShutdownStarted));
}
