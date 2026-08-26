using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class WirelessAdbEndpointTests
{
    [Theory]
    [InlineData("192.168.1.23:37123", "192.168.1.23", 37123)]
    [InlineData("  10.0.0.5:5555  ", "10.0.0.5", 5555)]
    [InlineData("phone.local:40001", "phone.local", 40001)]
    [InlineData("[fe80::1]:37123", "fe80::1", 37123)]
    [InlineData("host:1", "host", 1)]
    [InlineData("host:65535", "host", 65535)]
    public void ParsesValidEndpoints(string input, string host, int port)
    {
        Assert.True(WirelessAdbEndpoint.TryParse(input, out var endpoint));
        Assert.Equal(host, endpoint!.Host);
        Assert.Equal(port, endpoint.Port);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("192.168.1.23")] // no port
    [InlineData(":5555")] // no host
    [InlineData("192.168.1.23:")] // empty port
    [InlineData("192.168.1.23:0")] // port below range
    [InlineData("192.168.1.23:65536")] // port above range
    [InlineData("192.168.1.23:12ab")] // non-numeric port
    [InlineData("192.168.1.23:-1")] // sign is not a digit
    [InlineData("fe80::1:37123")] // bare IPv6: the port would be a guess
    [InlineData("[fe80::1]37123")] // bracket without colon
    [InlineData("[]:37123")] // empty bracketed host
    [InlineData("a b:37123")] // whitespace inside host
    public void RejectsInvalidEndpoints(string? input) =>
        Assert.False(WirelessAdbEndpoint.TryParse(input, out _));

    [Fact]
    public void ToStringRoundTripsIpv4()
    {
        Assert.True(WirelessAdbEndpoint.TryParse("192.168.1.23:37123", out var endpoint));
        Assert.Equal("192.168.1.23:37123", endpoint!.ToString());
    }

    [Fact]
    public void ToStringRebracketsIpv6ForAdb()
    {
        Assert.True(WirelessAdbEndpoint.TryParse("[fe80::1]:37123", out var endpoint));
        Assert.Equal("[fe80::1]:37123", endpoint!.ToString());
    }
}
