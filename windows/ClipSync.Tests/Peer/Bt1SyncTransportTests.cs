using System.Security.Cryptography;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Bluetooth;
using ClipSync.Peer.Transport;

namespace ClipSync.Tests.Peer;

public sealed class Bt1SyncTransportTests
{
    private static (Bt1SyncTransport ClientTransport, Bt1SyncTransport ListenerTransport, Stream ClientSide) CreatePair()
    {
        var clientToListenerKey = RandomNumberGenerator.GetBytes(32);
        var listenerToClientKey = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        var client = new Bt1SyncTransport(
            clientSide,
            new Bt1FrameEncryptor(clientToListenerKey),
            new Bt1FrameDecryptor(listenerToClientKey));
        var listener = new Bt1SyncTransport(
            listenerSide,
            new Bt1FrameEncryptor(listenerToClientKey),
            new Bt1FrameDecryptor(clientToListenerKey));
        return (client, listener, clientSide);
    }

    [Fact]
    public async Task TextMessagesRoundTripInBothDirectionsInOrder()
    {
        var (client, listener, _) = CreatePair();
        await using (client)
        await using (listener)
        {
            await client.SendTextAsync("""{"version":1,"type":"ping"}""", CancellationToken.None);
            await client.SendTextAsync("第二帧", CancellationToken.None);
            Assert.Equal(new TransportFrame.Text("""{"version":1,"type":"ping"}"""), await listener.ReceiveAsync(CancellationToken.None));
            Assert.Equal(new TransportFrame.Text("第二帧"), await listener.ReceiveAsync(CancellationToken.None));

            await listener.SendTextAsync("pong", CancellationToken.None);
            Assert.Equal(new TransportFrame.Text("pong"), await client.ReceiveAsync(CancellationToken.None));
        }
    }

    [Fact]
    public async Task GracefulCloseReadsAsClosedOnThePeerWithNoPlaintextOnTheWire()
    {
        var (client, listener, _) = CreatePair();
        await using (client)
        await using (listener)
        {
            await client.CloseAsync(System.Net.WebSockets.WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
            Assert.IsType<TransportFrame.Closed>(await listener.ReceiveAsync(CancellationToken.None));
        }
    }

    [Fact]
    public async Task ATamperedFrameKillsTheLinkInsteadOfDeliveringGarbage()
    {
        var clientToListenerKey = RandomNumberGenerator.GetBytes(32);
        var listenerToClientKey = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        {
            using var attacker = new Bt1FrameEncryptor(clientToListenerKey);
            var frame = attacker.EncryptFrame("about to be corrupted"u8.ToArray());
            frame[Bt1Frames.LengthPrefixLength + 2] ^= 0x40;
            await clientSide.WriteAsync(frame);
            await clientSide.FlushAsync();

            await using var listener = new Bt1SyncTransport(
                listenerSide,
                new Bt1FrameEncryptor(listenerToClientKey),
                new Bt1FrameDecryptor(clientToListenerKey));
            Assert.IsType<TransportFrame.Closed>(await listener.ReceiveAsync(CancellationToken.None));
        }
    }

    [Fact]
    public async Task AReplayedFrameKillsTheLink()
    {
        var clientToListenerKey = RandomNumberGenerator.GetBytes(32);
        var listenerToClientKey = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        {
            using var sender = new Bt1FrameEncryptor(clientToListenerKey);
            var frame = sender.EncryptFrame("once only"u8.ToArray());
            await clientSide.WriteAsync(frame);
            await clientSide.WriteAsync(frame);
            await clientSide.FlushAsync();

            await using var listener = new Bt1SyncTransport(
                listenerSide,
                new Bt1FrameEncryptor(listenerToClientKey),
                new Bt1FrameDecryptor(clientToListenerKey));
            Assert.Equal(new TransportFrame.Text("once only"), await listener.ReceiveAsync(CancellationToken.None));
            // The duplicate decrypts against counter 1 and fails the tag: fatal, not garbage.
            Assert.IsType<TransportFrame.Closed>(await listener.ReceiveAsync(CancellationToken.None));
        }
    }

    [Fact]
    public async Task SendAfterDisposeFailsInsteadOfWritingToADeadSocket()
    {
        var (client, listener, _) = CreatePair();
        await listener.DisposeAsync();
        await client.DisposeAsync();
        await Assert.ThrowsAsync<IOException>(
            async () => await client.SendTextAsync("too late", CancellationToken.None));
    }
}
