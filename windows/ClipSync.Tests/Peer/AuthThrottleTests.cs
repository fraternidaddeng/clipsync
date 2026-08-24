using ClipSync.Peer.Server;

namespace ClipSync.Tests.Peer;

public sealed class AuthThrottleTests
{
    private const string Device = "11111111-1111-1111-1111-111111111111";
    private const string OtherDevice = "22222222-2222-2222-2222-222222222222";

    [Fact]
    public void IsThrottledBecomesTrueOnlyAtThreshold()
    {
        var clock = new ManualClock();
        var throttle = new AuthThrottle(clock, maxFailures: 3, window: TimeSpan.FromSeconds(30));

        throttle.RecordAuthFailure(Device);
        throttle.RecordAuthFailure(Device);
        Assert.False(throttle.IsThrottled(Device));

        throttle.RecordAuthFailure(Device);
        Assert.True(throttle.IsThrottled(Device));
    }

    [Fact]
    public void DeviceLockedOutFiresOncePerEpisode()
    {
        var clock = new ManualClock();
        var throttle = new AuthThrottle(clock, maxFailures: 2, window: TimeSpan.FromSeconds(30));
        var lockouts = new List<string>();
        throttle.DeviceLockedOut += lockouts.Add;

        throttle.RecordAuthFailure(Device);
        Assert.Empty(lockouts);

        // Crossing the threshold announces exactly one lockout.
        throttle.RecordAuthFailure(Device);
        Assert.Equal(Device, Assert.Single(lockouts));

        // Further failures inside the same window do not re-announce.
        throttle.RecordAuthFailure(Device);
        Assert.Single(lockouts);
    }

    [Fact]
    public void DeviceLockedOutReArmsAfterWindowDrains()
    {
        var clock = new ManualClock();
        var window = TimeSpan.FromSeconds(30);
        var throttle = new AuthThrottle(clock, maxFailures: 2, window: window);
        var lockouts = new List<string>();
        throttle.DeviceLockedOut += lockouts.Add;

        throttle.RecordAuthFailure(Device);
        throttle.RecordAuthFailure(Device);
        Assert.Single(lockouts);

        // Let the window drain: the device is no longer throttled.
        clock.Advance(window + TimeSpan.FromSeconds(1));
        Assert.False(throttle.IsThrottled(Device));

        // A fresh burst announces a new lockout episode.
        throttle.RecordAuthFailure(Device);
        throttle.RecordAuthFailure(Device);
        Assert.Equal(2, lockouts.Count);
    }

    [Fact]
    public void ThrottledDevicesReflectsWindowExpiry()
    {
        var clock = new ManualClock();
        var window = TimeSpan.FromSeconds(30);
        var throttle = new AuthThrottle(clock, maxFailures: 2, window: window);

        throttle.RecordAuthFailure(Device);
        throttle.RecordAuthFailure(Device);
        Assert.Equal(Device, Assert.Single(throttle.ThrottledDevices()));

        clock.Advance(window + TimeSpan.FromSeconds(1));
        Assert.Empty(throttle.ThrottledDevices());
    }

    [Fact]
    public void ThrottleIsKeyedPerDevice()
    {
        var clock = new ManualClock();
        var throttle = new AuthThrottle(clock, maxFailures: 2, window: TimeSpan.FromSeconds(30));

        throttle.RecordAuthFailure(Device);
        throttle.RecordAuthFailure(Device);

        Assert.True(throttle.IsThrottled(Device));
        Assert.False(throttle.IsThrottled(OtherDevice));
    }

    private sealed class ManualClock : TimeProvider
    {
        private DateTimeOffset now = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

        public override DateTimeOffset GetUtcNow() => now;

        public void Advance(TimeSpan by) => now += by;
    }
}
