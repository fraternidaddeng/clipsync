using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class WirelessAdbOutcomeTests
{
    // ===== adb pair =====

    [Fact]
    public void PairSuccessLineIsRecognized()
    {
        var outcome = AdbPairOutcome.FromAdbRun(
            0, "Successfully paired to 192.168.1.10:37123 [guid=adb-RF8N123456-aBcDeF]\n", string.Empty);

        Assert.True(outcome.Succeeded);
        Assert.Equal(AdbPairStatus.Paired, outcome.Status);
        Assert.Contains("Successfully paired", outcome.Detail, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("Failed: Wrong password or connection was dropped.\n")]
    [InlineData("Failed: Unable to start pairing client.\n")]
    public void PairFailedLineIsARejectionCarryingAdbsReason(string stdout)
    {
        var outcome = AdbPairOutcome.FromAdbRun(0, stdout, string.Empty);

        Assert.False(outcome.Succeeded);
        Assert.Equal(AdbPairStatus.Rejected, outcome.Status);
        Assert.StartsWith("Failed:", outcome.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public void PairNonZeroExitWithoutVerdictIsAdbFailure()
    {
        var outcome = AdbPairOutcome.FromAdbRun(1, string.Empty, "adb: protocol fault (couldn't read status)\n");

        Assert.Equal(AdbPairStatus.AdbFailed, outcome.Status);
        Assert.Contains("protocol fault", outcome.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public void PairZeroExitWithoutSuccessMarkerIsNeverReportedAsSuccess()
    {
        var outcome = AdbPairOutcome.FromAdbRun(0, "something unexpected\n", string.Empty);

        Assert.False(outcome.Succeeded);
        Assert.Equal(AdbPairStatus.Rejected, outcome.Status);
    }

    // ===== adb connect =====

    [Fact]
    public void ConnectedLineIsSuccess()
    {
        var outcome = AdbConnectOutcome.FromAdbRun(0, "connected to 192.168.1.10:40331\n", string.Empty);

        Assert.True(outcome.Succeeded);
        Assert.Equal(AdbConnectStatus.Connected, outcome.Status);
    }

    [Fact]
    public void AlreadyConnectedIsAlsoSuccessAndNotMisreadAsConnected()
    {
        var outcome = AdbConnectOutcome.FromAdbRun(0, "already connected to 192.168.1.10:40331\n", string.Empty);

        Assert.True(outcome.Succeeded);
        Assert.Equal(AdbConnectStatus.AlreadyConnected, outcome.Status);
    }

    [Theory]
    [InlineData("failed to connect to '192.168.1.10:40331': Connection refused\n")]
    [InlineData("cannot connect to 192.168.1.10:40331: No route to host\n")]
    [InlineData("unable to connect to 192.168.1.10:40331\n")]
    public void RefusalLinesAreRefusedEvenWhenAdbExitsZero(string stdout)
    {
        // Several adb builds exit 0 while printing a connect failure; text must win.
        var outcome = AdbConnectOutcome.FromAdbRun(0, stdout, string.Empty);

        Assert.False(outcome.Succeeded);
        Assert.Equal(AdbConnectStatus.Refused, outcome.Status);
        Assert.NotNull(outcome.Detail);
    }

    [Fact]
    public void ConnectNonZeroExitWithoutVerdictIsAdbFailure()
    {
        var outcome = AdbConnectOutcome.FromAdbRun(1, string.Empty, "adb: device offline\n");

        Assert.Equal(AdbConnectStatus.AdbFailed, outcome.Status);
    }

    [Fact]
    public void ConnectZeroExitWithoutVerdictIsNeverReportedAsSuccess()
    {
        var outcome = AdbConnectOutcome.FromAdbRun(0, "\n", string.Empty);

        Assert.False(outcome.Succeeded);
        Assert.Equal(AdbConnectStatus.Refused, outcome.Status);
    }
}
