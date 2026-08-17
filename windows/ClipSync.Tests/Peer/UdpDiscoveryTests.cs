using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using ClipSync.Peer.Discovery;

namespace ClipSync.Tests.Peer;

public sealed class UdpDiscoveryTests
{
    [Fact]
    public async Task BeaconCarriesOnlyDeviceIdPortAndFingerprint()
    {
        using var listener = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        var target = (IPEndPoint)listener.Client.LocalEndPoint!;

        const string deviceId = "11111111-1111-4111-8111-111111111111";
        const string fingerprint = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25";
        using var broadcaster = new UdpDiscoveryBroadcaster(deviceId, 40404, fingerprint, targetOverride: target);
        await broadcaster.BroadcastOnceAsync();

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var datagram = await listener.ReceiveAsync(cts.Token);
        var payload = Encoding.UTF8.GetString(datagram.Buffer);

        using var document = JsonDocument.Parse(payload);
        var root = document.RootElement;
        Assert.Equal(1, root.GetProperty("v").GetInt32());
        Assert.Equal("clipsync_discovery", root.GetProperty("kind").GetString());
        Assert.Equal(deviceId, root.GetProperty("device_id").GetString());
        Assert.Equal(40404, root.GetProperty("port").GetInt32());
        Assert.Equal(fingerprint, root.GetProperty("cert_sha256").GetString());

        // Exactly five fields: nothing else may leak into the beacon.
        Assert.Equal(5, root.EnumerateObject().Count());
    }
}
