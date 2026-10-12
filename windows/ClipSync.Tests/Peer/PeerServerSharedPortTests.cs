using System.Net;
using System.Net.Sockets;
using ClipSync.Core.Storage;
using ClipSync.Peer.Security;
using ClipSync.Peer.Server;

namespace ClipSync.Tests.Peer;

public sealed class PeerServerSharedPortTests
{
    [Fact]
    public async Task EphemeralPortIsSharedAcrossAllBindAddresses()
    {
        if (!OperatingSystem.IsLinux())
        {
            // 127.0.0.2 is bindable out of the box on Linux; Windows loopback aliases are not.
            return;
        }

        var directory = Path.Combine(Path.GetTempPath(), "clipsync-port-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var deviceId = Guid.NewGuid().ToString("D");
        await using var store = new SqliteClipboardEventStore(Path.Combine(directory, "w.db"), deviceId);
        await store.InitializeAsync();
        using var certificate = PeerCertificate.CreateSelfSigned(deviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(30));
        var second = IPAddress.Parse("127.0.0.2");
        await using var server = new PeerServer(store, new FakeSecretProtector(), new PeerServerOptions
        {
            Certificate = certificate,
            SessionOptions = PeerPair.DefaultSessionOptions(),
            BindAddresses = [IPAddress.Loopback, second],
            Port = 0
        });

        await server.StartAsync();

        Assert.NotEqual(0, server.Port);
        foreach (var address in new[] { IPAddress.Loopback, second })
        {
            using var client = new TcpClient();
            await client.ConnectAsync(address, server.Port);
            Assert.True(client.Connected, $"{address}:{server.Port} must accept");
        }
    }
}
