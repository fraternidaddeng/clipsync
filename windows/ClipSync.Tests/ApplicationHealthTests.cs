using ClipSync.Core.Health;

namespace ClipSync.Tests;

public sealed class ApplicationHealthTests
{
    [Fact]
    public void CreateReturnsReadyWindowsHealth()
    {
        var health = ApplicationHealth.Create();

        Assert.Equal("ready", health.Status);
        Assert.Equal(1, health.ProtocolVersion);
        Assert.Equal("windows", health.Platform);
    }
}
