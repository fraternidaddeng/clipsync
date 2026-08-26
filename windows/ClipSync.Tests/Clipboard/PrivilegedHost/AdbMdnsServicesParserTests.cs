using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class AdbMdnsServicesParserTests
{
    [Fact]
    public void ParsesPairingAndConnectRows()
    {
        const string stdout =
            "List of discovered mdns services\n" +
            "adb-RF8N123456-aBcDeF\t_adb-tls-connect._tcp.\t192.168.1.10:40331\n" +
            "clipsync-k3m9p2q7rt\t_adb-tls-pairing._tcp.\t192.168.1.10:37123\n";

        var rows = AdbMdnsServicesParser.Parse(stdout);

        Assert.Equal(2, rows.Count);
        Assert.Equal("adb-RF8N123456-aBcDeF", rows[0].InstanceName);
        Assert.True(rows[0].IsOfType(AdbMdnsServicesParser.ConnectServiceType));
        Assert.Equal(new WirelessAdbEndpoint("192.168.1.10", 40331), rows[0].Endpoint);
        Assert.Equal("clipsync-k3m9p2q7rt", rows[1].InstanceName);
        Assert.True(rows[1].IsOfType(AdbMdnsServicesParser.PairingServiceType));
        Assert.Equal(new WirelessAdbEndpoint("192.168.1.10", 37123), rows[1].Endpoint);
    }

    [Fact]
    public void TypeMatchIgnoresTheTrailingDot()
    {
        var withDot = new AdbMdnsServiceRow("x", "_adb-tls-pairing._tcp.", null);
        var withoutDot = new AdbMdnsServiceRow("x", "_adb-tls-pairing._tcp", null);

        Assert.True(withDot.IsOfType(AdbMdnsServicesParser.PairingServiceType));
        Assert.True(withoutDot.IsOfType(AdbMdnsServicesParser.PairingServiceType));
        Assert.False(withDot.IsOfType(AdbMdnsServicesParser.ConnectServiceType));
    }

    [Fact]
    public void UnparsableEndpointIsKeptAsRowWithNullEndpoint()
    {
        const string stdout = "svc\t_adb-tls-connect._tcp.\tnot-an-endpoint\n";

        var rows = AdbMdnsServicesParser.Parse(stdout);

        Assert.Single(rows);
        Assert.Null(rows[0].Endpoint);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("List of discovered mdns services\n")]
    public void EmptyOrHeaderOnlyOutputYieldsNoRows(string? stdout) =>
        Assert.Empty(AdbMdnsServicesParser.Parse(stdout));

    [Fact]
    public void FallsBackToWhitespaceColumnsWhenTabsAreMissing()
    {
        const string stdout = "svc   _adb-tls-pairing._tcp.   10.0.0.7:41000\n";

        var rows = AdbMdnsServicesParser.Parse(stdout);

        Assert.Single(rows);
        Assert.Equal(new WirelessAdbEndpoint("10.0.0.7", 41000), rows[0].Endpoint);
    }

    [Fact]
    public void WindowsLineEndingsParseTheSame()
    {
        const string stdout =
            "List of discovered mdns services\r\n" +
            "clipsync-abc\t_adb-tls-pairing._tcp.\t192.168.0.2:4444\r\n";

        var rows = AdbMdnsServicesParser.Parse(stdout);

        Assert.Single(rows);
        Assert.Equal("clipsync-abc", rows[0].InstanceName);
    }
}
