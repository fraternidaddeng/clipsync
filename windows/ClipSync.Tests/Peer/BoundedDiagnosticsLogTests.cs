using ClipSync.Peer.Diagnostics;

namespace ClipSync.Tests.Peer;

public sealed class BoundedDiagnosticsLogTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);
    private static readonly string[] NewestFirst = ["third", "second", "first"];
    private static readonly string[] LastThree = ["code-4", "code-3", "code-2"];

    [Fact]
    public void SnapshotIsNewestFirst()
    {
        var log = new BoundedDiagnosticsLog();
        log.Record("first", T0);
        log.Record("second", T0.AddSeconds(1));
        log.Record("third", T0.AddSeconds(2));

        var snapshot = log.Snapshot();
        Assert.Equal(NewestFirst, snapshot.Select(entry => entry.Code));
    }

    [Fact]
    public void RecordDropsOldestBeyondCapacity()
    {
        var log = new BoundedDiagnosticsLog(capacity: 3);
        for (var i = 0; i < 5; i++)
        {
            log.Record($"code-{i}", T0.AddSeconds(i));
        }

        var snapshot = log.Snapshot();
        Assert.Equal(3, snapshot.Count);
        Assert.Equal(LastThree, snapshot.Select(entry => entry.Code));
    }

    [Fact]
    public void RecordIgnoresBlankCodes()
    {
        var log = new BoundedDiagnosticsLog();
        log.Record("", T0);
        log.Record("   ", T0);
        log.Record("real", T0);

        Assert.Equal("real", Assert.Single(log.Snapshot()).Code);
    }

    [Fact]
    public void ClearEmptiesTheBuffer()
    {
        var log = new BoundedDiagnosticsLog();
        log.Record("code", T0);
        log.Clear();

        Assert.Empty(log.Snapshot());
        Assert.Equal(0, log.Count);
    }

    [Fact]
    public void ConstructorRejectsNonPositiveCapacity()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new BoundedDiagnosticsLog(0));
    }
}
