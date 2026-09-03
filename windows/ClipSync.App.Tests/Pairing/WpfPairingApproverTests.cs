using ClipSync.App.Pairing;
using ClipSync.Peer.Pairing;

namespace ClipSync.App.Tests.Pairing;

public sealed class WpfPairingApproverTests
{
    private static PairingCandidate Candidate(CancellationToken approvalTimeout = default) =>
        new("33333333-3333-4333-8333-333333333333", "Pixel 8", "android", IsRepair: false)
        {
            ApprovalTimeout = approvalTimeout
        };

    [Fact]
    public void ApprovalTimeoutFiringReadsAsTimeoutAndPicksTheTimeoutBalloon()
    {
        using var timeout = new CancellationTokenSource();
        using var request = new CancellationTokenSource();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(request.Token, timeout.Token);
        var candidate = Candidate(timeout.Token);
        var timedOut = 0;
        var aborted = 0;
        Action onTimedOut = () => timedOut++;
        Action onAborted = () => aborted++;

        // Mirrors PairingService.ConfirmAsync: the approver observes the linked token, the
        // 90-second source is what actually fired.
        timeout.Cancel();
        Assert.True(linked.IsCancellationRequested);
        var kind = WpfPairingApprover.Classify(candidate);
        WpfPairingApprover.NoticeFor(kind, onTimedOut, onAborted)?.Invoke();

        Assert.Equal(WpfPairingApprover.CancellationKind.Timeout, kind);
        Assert.Equal(1, timedOut);
        Assert.Equal(0, aborted);
    }

    [Fact]
    public void RequestAbortedBeforeTheWaitElapsedReadsAsAbortedAndPicksTheAbortedBalloon()
    {
        using var timeout = new CancellationTokenSource();
        using var request = new CancellationTokenSource();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(request.Token, timeout.Token);
        var candidate = Candidate(timeout.Token);
        var timedOut = 0;
        var aborted = 0;
        Action onTimedOut = () => timedOut++;
        Action onAborted = () => aborted++;

        // The phone hung up (Kestrel's RequestAborted): the linked token fires, the timeout did not.
        request.Cancel();
        Assert.True(linked.IsCancellationRequested);
        var kind = WpfPairingApprover.Classify(candidate);
        WpfPairingApprover.NoticeFor(kind, onTimedOut, onAborted)?.Invoke();

        Assert.Equal(WpfPairingApprover.CancellationKind.Aborted, kind);
        Assert.Equal(0, timedOut);
        Assert.Equal(1, aborted);
    }

    [Fact]
    public void AbortWithoutAWiredAbortBalloonStaysSilentInsteadOfClaimingATimeout()
    {
        using var timeout = new CancellationTokenSource();
        var candidate = Candidate(timeout.Token);
        var timedOut = 0;

        var kind = WpfPairingApprover.Classify(candidate);
        var notice = WpfPairingApprover.NoticeFor(kind, () => timedOut++, onAborted: null);

        Assert.Equal(WpfPairingApprover.CancellationKind.Aborted, kind);
        Assert.Null(notice);
        Assert.Equal(0, timedOut);
    }

    [Fact]
    public void CandidateWithoutADistinguishableTimeoutTokenKeepsTheTimeoutReading()
    {
        Assert.Equal(WpfPairingApprover.CancellationKind.Timeout, WpfPairingApprover.Classify(Candidate()));
    }

    [Fact]
    public void ShortNamesReachTheBalloonTrimmedButIntact()
    {
        Assert.Equal("Pixel 8", WpfPairingApprover.NoticeName("  Pixel 8 "));
        Assert.Equal(new string('a', 40), WpfPairingApprover.NoticeName(new string('a', 40)));
    }

    [Fact]
    public void LongNamesAreCutToTheBalloonBudgetWithAnEllipsis()
    {
        var name = WpfPairingApprover.NoticeName(new string('a', 64));

        Assert.Equal(40, name.Length);
        Assert.EndsWith("…", name, StringComparison.Ordinal);
        Assert.StartsWith(new string('a', 39), name, StringComparison.Ordinal);
    }

    [Fact]
    public void TheCutNeverSplitsASurrogatePair()
    {
        // 38 ASCII chars, then an emoji straddling the cut index (chars 38-39), then more text.
        var name = WpfPairingApprover.NoticeName(new string('b', 38) + "😀" + new string('c', 10));

        Assert.EndsWith("…", name, StringComparison.Ordinal);
        Assert.All(name, character => Assert.False(char.IsSurrogate(character)));
        Assert.Equal(new string('b', 38) + "…", name);
    }
}
