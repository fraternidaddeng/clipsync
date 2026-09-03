using ClipSync.Peer.Discovery;

namespace ClipSync.Tests.Peer;

public sealed class DiscoveryBeaconScheduleTests
{
    [Fact]
    public void StartsIdleAtFiveMinutes()
    {
        var schedule = new DiscoveryBeaconSchedule();

        Assert.False(schedule.PairingActive);
        Assert.Equal(TimeSpan.FromMinutes(5), schedule.CurrentInterval);
        Assert.Equal(DiscoveryBeaconSchedule.IdleInterval, schedule.CurrentInterval);
    }

    [Fact]
    public void FirstHoldSwitchesToTheTwoSecondCadenceAndAsksForAnImmediateBeacon()
    {
        var schedule = new DiscoveryBeaconSchedule();

        Assert.True(schedule.BeginPairing());
        Assert.True(schedule.PairingActive);
        Assert.Equal(TimeSpan.FromSeconds(2), schedule.CurrentInterval);
        Assert.Equal(DiscoveryBeaconSchedule.PairingInterval, schedule.CurrentInterval);
    }

    [Fact]
    public void NestedHoldsAreReferenceCounted()
    {
        var schedule = new DiscoveryBeaconSchedule();

        Assert.True(schedule.BeginPairing());
        // A second QR window (or a re-issued ticket) must not restart the cadence.
        Assert.False(schedule.BeginPairing());

        // Releasing one of two holds keeps the dense cadence.
        Assert.False(schedule.EndPairing());
        Assert.True(schedule.PairingActive);
        Assert.Equal(DiscoveryBeaconSchedule.PairingInterval, schedule.CurrentInterval);

        // The last release is the one that flips back to idle.
        Assert.True(schedule.EndPairing());
        Assert.False(schedule.PairingActive);
        Assert.Equal(DiscoveryBeaconSchedule.IdleInterval, schedule.CurrentInterval);
    }

    [Fact]
    public void ReleasingWithoutAHoldIsANoOp()
    {
        var schedule = new DiscoveryBeaconSchedule();

        Assert.False(schedule.EndPairing());
        Assert.False(schedule.PairingActive);

        // An unbalanced release must not leave a negative count that swallows the next hold.
        Assert.True(schedule.BeginPairing());
        Assert.True(schedule.EndPairing());
        Assert.Equal(DiscoveryBeaconSchedule.IdleInterval, schedule.CurrentInterval);
    }

    [Fact]
    public void CadenceCanBeReenteredAfterReturningToIdle()
    {
        var schedule = new DiscoveryBeaconSchedule();
        schedule.BeginPairing();
        schedule.EndPairing();

        Assert.True(schedule.BeginPairing());
        Assert.Equal(DiscoveryBeaconSchedule.PairingInterval, schedule.CurrentInterval);
    }
}
