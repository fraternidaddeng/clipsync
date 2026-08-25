using ClipSync.Peer.Sessions;

namespace ClipSync.Tests.Peer;

public sealed class FrameRateBudgetTests
{
    [Fact]
    public void AdmitsUpToTheBudgetThenRefuses()
    {
        var clock = new ManualClock();
        var budget = new FrameRateBudget(clock, maxFrames: 3, TimeSpan.FromMinutes(1));

        Assert.True(budget.TryAdmit());
        Assert.True(budget.TryAdmit());
        Assert.True(budget.TryAdmit());
        Assert.False(budget.TryAdmit());
        Assert.False(budget.TryAdmit());
    }

    [Fact]
    public void BudgetResetsWhenTheWindowRollsOver()
    {
        var clock = new ManualClock();
        var budget = new FrameRateBudget(clock, maxFrames: 2, TimeSpan.FromSeconds(60));

        Assert.True(budget.TryAdmit());
        Assert.True(budget.TryAdmit());
        Assert.False(budget.TryAdmit());

        clock.Advance(TimeSpan.FromSeconds(60));
        Assert.True(budget.TryAdmit());
        Assert.True(budget.TryAdmit());
        Assert.False(budget.TryAdmit());
    }

    [Fact]
    public void FramesSpreadAcrossWindowsNeverRefuse()
    {
        var clock = new ManualClock();
        var budget = new FrameRateBudget(clock, maxFrames: 1, TimeSpan.FromSeconds(1));

        for (var frame = 0; frame < 10; frame++)
        {
            Assert.True(budget.TryAdmit());
            clock.Advance(TimeSpan.FromSeconds(1));
        }
    }

    [Fact]
    public void RefusalsInsideTheWindowDoNotExtendIt()
    {
        var clock = new ManualClock();
        var budget = new FrameRateBudget(clock, maxFrames: 1, TimeSpan.FromSeconds(10));

        Assert.True(budget.TryAdmit());
        clock.Advance(TimeSpan.FromSeconds(9));
        Assert.False(budget.TryAdmit());

        // One more second completes the original window; the flood does not push it out.
        clock.Advance(TimeSpan.FromSeconds(1));
        Assert.True(budget.TryAdmit());
    }

    private sealed class ManualClock : TimeProvider
    {
        private DateTimeOffset now = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

        public override DateTimeOffset GetUtcNow() => now;

        public void Advance(TimeSpan by) => now += by;
    }
}
