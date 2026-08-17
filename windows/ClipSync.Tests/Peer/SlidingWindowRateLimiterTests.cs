using ClipSync.Peer.Server;

namespace ClipSync.Tests.Peer;

public sealed class SlidingWindowRateLimiterTests
{
    [Fact]
    public void AdmitsUpToTheLimitThenRefusesUntilTheWindowSlides()
    {
        var clock = new ManualClock();
        var limiter = new SlidingWindowRateLimiter(clock, maxEvents: 2, TimeSpan.FromMinutes(1));

        Assert.True(limiter.TryAdmit("10.0.0.1"));
        Assert.True(limiter.TryAdmit("10.0.0.1"));
        Assert.False(limiter.TryAdmit("10.0.0.1"));

        Assert.True(limiter.TryAdmit("10.0.0.2"));

        clock.Advance(TimeSpan.FromMinutes(1) + TimeSpan.FromMilliseconds(1));
        Assert.True(limiter.TryAdmit("10.0.0.1"));
        Assert.True(limiter.TryAdmit("10.0.0.1"));
        Assert.False(limiter.TryAdmit("10.0.0.1"));
    }

    [Fact]
    public void WindowIsSlidingNotFixedToTheFirstEvent()
    {
        var clock = new ManualClock();
        var limiter = new SlidingWindowRateLimiter(clock, maxEvents: 2, TimeSpan.FromSeconds(60));

        Assert.True(limiter.TryAdmit("10.0.0.1"));
        clock.Advance(TimeSpan.FromSeconds(50));
        Assert.True(limiter.TryAdmit("10.0.0.1"));
        Assert.False(limiter.TryAdmit("10.0.0.1"));

        // The first event ages out once it is strictly older than the window.
        clock.Advance(TimeSpan.FromSeconds(10) + TimeSpan.FromMilliseconds(1));
        Assert.True(limiter.TryAdmit("10.0.0.1"));
        Assert.False(limiter.TryAdmit("10.0.0.1"));
    }

    [Fact]
    public void FailedAndSuccessfulAttemptsShareTheSameBudget()
    {
        var clock = new ManualClock();
        var limiter = new SlidingWindowRateLimiter(clock, maxEvents: 10, TimeSpan.FromMinutes(1));

        for (var attempt = 0; attempt < 10; attempt++)
        {
            Assert.True(limiter.TryAdmit("10.0.0.1"));
        }

        Assert.False(limiter.TryAdmit("10.0.0.1"));
    }

    private sealed class ManualClock : TimeProvider
    {
        private DateTimeOffset now = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

        public override DateTimeOffset GetUtcNow() => now;

        public void Advance(TimeSpan by) => now += by;
    }
}
