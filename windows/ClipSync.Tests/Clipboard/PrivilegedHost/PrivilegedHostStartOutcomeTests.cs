using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class PrivilegedHostStartOutcomeTests
{
    [Fact]
    public void SpawnedMarkerIsSuccess()
    {
        var outcome = PrivilegedHostStartOutcome.FromAdbRun(
            0,
            "info: clipsync privileged host begin\ninfo: apk /data/app/base.apk\ninfo: spawned\n",
            string.Empty);
        Assert.True(outcome.Succeeded);
        Assert.Equal(PrivilegedHostStartStatus.Started, outcome.Status);
    }

    [Fact]
    public void FatalLineIsScriptFailureCarryingItsReason()
    {
        var outcome = PrivilegedHostStartOutcome.FromAdbRun(
            7,
            "info: clipsync privileged host begin\nfatal: apk not found\n",
            string.Empty);
        Assert.False(outcome.Succeeded);
        Assert.Equal(PrivilegedHostStartStatus.ScriptFailed, outcome.Status);
        Assert.Equal("fatal: apk not found", outcome.Reason);
    }

    [Fact]
    public void FatalWinsEvenWhenExitCodeIsZero()
    {
        var outcome = PrivilegedHostStartOutcome.FromAdbRun(0, "fatal: uid 10123 is not shell or root\n", string.Empty);
        Assert.Equal(PrivilegedHostStartStatus.ScriptFailed, outcome.Status);
    }

    [Fact]
    public void AdbTransportErrorIsAdbFailure()
    {
        var outcome = PrivilegedHostStartOutcome.FromAdbRun(
            1,
            string.Empty,
            "adb: no devices/emulators found");
        Assert.Equal(PrivilegedHostStartStatus.AdbFailed, outcome.Status);
        Assert.Equal("adb: no devices/emulators found", outcome.Reason);
    }

    [Fact]
    public void ExitZeroWithoutSpawnedIsNotFalselyReportedAsSuccess()
    {
        var outcome = PrivilegedHostStartOutcome.FromAdbRun(0, "info: starting clipsync_priv_server\n", string.Empty);
        Assert.False(outcome.Succeeded);
        Assert.Equal(PrivilegedHostStartStatus.ScriptFailed, outcome.Status);
    }
}
