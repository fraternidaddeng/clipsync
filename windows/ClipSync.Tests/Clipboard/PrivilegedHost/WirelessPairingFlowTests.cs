using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class WirelessPairingFlowTests
{
    [Fact]
    public void QrHappyPathWalksScanPairConnect()
    {
        var flow = new WirelessPairingFlow();

        Assert.True(flow.TryApply(WirelessPairingEvent.QrShown));
        Assert.Equal(WirelessPairingStage.AwaitingScan, flow.Stage);
        Assert.True(flow.TryApply(WirelessPairingEvent.PairingServiceDiscovered));
        Assert.Equal(WirelessPairingStage.Pairing, flow.Stage);
        Assert.True(flow.TryApply(WirelessPairingEvent.PairSucceeded));
        Assert.Equal(WirelessPairingStage.Connecting, flow.Stage);
        Assert.True(flow.TryApply(WirelessPairingEvent.ConnectSucceeded));
        Assert.Equal(WirelessPairingStage.Connected, flow.Stage);
    }

    [Fact]
    public void ManualCodeHappyPathSkipsTheScanStage()
    {
        var flow = new WirelessPairingFlow();

        Assert.True(flow.TryApply(WirelessPairingEvent.ManualPairSubmitted));
        Assert.Equal(WirelessPairingStage.Pairing, flow.Stage);
        Assert.True(flow.TryApply(WirelessPairingEvent.PairSucceeded));
        Assert.True(flow.TryApply(WirelessPairingEvent.ConnectSucceeded));
        Assert.Equal(WirelessPairingStage.Connected, flow.Stage);
    }

    [Fact]
    public void DirectConnectPathForAnAlreadyPairedPhone()
    {
        var flow = new WirelessPairingFlow();

        Assert.True(flow.TryApply(WirelessPairingEvent.ConnectRequested));
        Assert.Equal(WirelessPairingStage.Connecting, flow.Stage);
        Assert.True(flow.TryApply(WirelessPairingEvent.ConnectSucceeded));
        Assert.Equal(WirelessPairingStage.Connected, flow.Stage);
    }

    [Fact]
    public void FailuresLandInFailedAndEveryEntryActionReopens()
    {
        var flow = new WirelessPairingFlow();
        flow.TryApply(WirelessPairingEvent.ManualPairSubmitted);
        Assert.True(flow.TryApply(WirelessPairingEvent.PairFailed));
        Assert.Equal(WirelessPairingStage.Failed, flow.Stage);

        // From Failed the user may retry any entry: QR, manual pair, or direct connect.
        Assert.True(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Failed, WirelessPairingEvent.QrShown, out var next));
        Assert.Equal(WirelessPairingStage.AwaitingScan, next);
        Assert.True(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Failed, WirelessPairingEvent.ManualPairSubmitted, out next));
        Assert.Equal(WirelessPairingStage.Pairing, next);
        Assert.True(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Failed, WirelessPairingEvent.ConnectRequested, out next));
        Assert.Equal(WirelessPairingStage.Connecting, next);
    }

    [Fact]
    public void CancelAlwaysReturnsToIdle()
    {
        foreach (var stage in Enum.GetValues<WirelessPairingStage>())
        {
            Assert.True(WirelessPairingFlow.TryAdvance(stage, WirelessPairingEvent.Cancelled, out var next));
            Assert.Equal(WirelessPairingStage.Idle, next);
        }
    }

    [Fact]
    public void StaleCompletionsCannotYankTheStage()
    {
        // A pair verdict arriving after the user cancelled must not move Idle anywhere.
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Idle, WirelessPairingEvent.PairSucceeded, out _));
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Idle, WirelessPairingEvent.PairFailed, out _));
        // A late mDNS hit after pairing already started is refused too.
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Pairing, WirelessPairingEvent.PairingServiceDiscovered, out _));
        // Connect verdicts only mean something while connecting.
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Connected, WirelessPairingEvent.ConnectFailed, out _));
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.AwaitingScan, WirelessPairingEvent.ConnectSucceeded, out _));
    }

    [Fact]
    public void EntryActionsAreRefusedMidFlight()
    {
        // While adb pair / adb connect is in flight the entry buttons must stay dead.
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Pairing, WirelessPairingEvent.QrShown, out _));
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Pairing, WirelessPairingEvent.ManualPairSubmitted, out _));
        Assert.False(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Connecting, WirelessPairingEvent.ConnectRequested, out _));
    }

    [Fact]
    public void RefusedEventLeavesInstanceStageUntouched()
    {
        var flow = new WirelessPairingFlow();

        Assert.False(flow.TryApply(WirelessPairingEvent.PairSucceeded));
        Assert.Equal(WirelessPairingStage.Idle, flow.Stage);
    }

    [Fact]
    public void ConnectedIsSettledSoANewSessionCanStartWithoutReset()
    {
        Assert.True(WirelessPairingFlow.TryAdvance(WirelessPairingStage.Connected, WirelessPairingEvent.QrShown, out var next));
        Assert.Equal(WirelessPairingStage.AwaitingScan, next);
    }
}
