using ClipSync.App.Startup;

namespace ClipSync.App.Tests.Startup;

/// <summary>
/// 运行 · 开机自启（P0-3）: the Run-entry command line, the --minimized detection the
/// app layer keys on, and the per-user registry round trip (HKCU only — safe on CI).
/// </summary>
public sealed class StartupRegistrationTests
{
    [Fact]
    public void CommandQuotesTheExecutableAndAppendsTheSilentArgument() =>
        Assert.Equal(
            "\"C:\\Apps\\Clip Sync\\ClipSync.App.exe\" --minimized",
            StartupRegistration.BuildCommand("C:\\Apps\\Clip Sync\\ClipSync.App.exe"));

    [Fact]
    public void MinimizedLaunchIsDetectedExactly()
    {
        Assert.True(StartupRegistration.IsMinimizedLaunch(["--minimized"]));
        Assert.True(StartupRegistration.IsMinimizedLaunch(["other", "--minimized"]));
        Assert.False(StartupRegistration.IsMinimizedLaunch([]));
        Assert.False(StartupRegistration.IsMinimizedLaunch(["--Minimized"]));
        Assert.False(StartupRegistration.IsMinimizedLaunch(["minimized"]));
    }

    [Theory]
    [InlineData(null, false)]
    [InlineData(new byte[0], false)]
    [InlineData(new byte[] { 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, false)]
    [InlineData(new byte[] { 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, false)]
    [InlineData(new byte[] { 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, true)]
    [InlineData(new byte[] { 0x07, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, true)]
    public void TaskManagerDisableFlagIsTheLowBitOfTheFirstByte(byte[]? flags, bool disabled) =>
        Assert.Equal(disabled, StartupRegistration.IsDisabledByStartupApproval(flags));

    [Fact]
    public void RunEntryRoundTripsThroughThePerUserRegistry()
    {
        // A test-only value name keeps this away from any real ClipSync entry.
        var valueName = $"ClipSync.Tests.{Guid.NewGuid():N}";
        try
        {
            Assert.False(StartupRegistration.IsEnabled(valueName));
            Assert.Equal(StartupRegistrationState.NotRegistered, StartupRegistration.Probe(valueName));

            StartupRegistration.SetEnabled(true, "C:\\Test\\ClipSync.App.exe", valueName);
            Assert.True(StartupRegistration.IsEnabled(valueName));
            // Task Manager has never touched a fresh test entry, so Windows will honour it.
            Assert.Equal(StartupRegistrationState.Registered, StartupRegistration.Probe(valueName));

            // Re-asserting is idempotent and refreshes the stored path.
            StartupRegistration.SetEnabled(true, "C:\\Moved\\ClipSync.App.exe", valueName);
            Assert.True(StartupRegistration.IsEnabled(valueName));

            StartupRegistration.SetEnabled(false, valueName: valueName);
            Assert.False(StartupRegistration.IsEnabled(valueName));

            // Disabling an absent entry is a no-op, not an error.
            StartupRegistration.SetEnabled(false, valueName: valueName);
            Assert.False(StartupRegistration.IsEnabled(valueName));
        }
        finally
        {
            StartupRegistration.SetEnabled(false, valueName: valueName);
        }
    }
}
