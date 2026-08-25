using System.Security.Cryptography;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Bluetooth;
using ClipSync.Peer.Sessions;

namespace ClipSync.Tests.Peer;

/// <summary>
/// End-to-end coverage of the Bluetooth fallback listener: a real dialer-role
/// <see cref="SyncSessionEngine"/> (Android's role) runs a protocol v1 session against
/// <see cref="BluetoothSyncHost"/> over an in-memory RFCOMM stand-in, through a genuine
/// bt1 handshake and encrypted frames — no radio, no WinRT.
/// </summary>
public sealed class BluetoothSyncHostTests
{
    private static BluetoothSyncHostOptions HostOptions() => new()
    {
        SessionOptions = PeerPair.DefaultSessionOptions()
    };

    private static async Task<DialedSession> DialOverBluetoothAsync(PeerPair pair, Stream clientSide, byte[] secret)
    {
        var device = await pair.AndroidStore.GetDeviceAsync(PeerPair.WindowsDeviceId, CancellationToken.None);
        Assert.NotNull(device);
        var (send, receive) = await Bt1TestClient.HandshakeAsync(
            clientSide,
            secret,
            Guid.Parse(PeerPair.AndroidDeviceId),
            Guid.Parse(PeerPair.WindowsDeviceId),
            device!.TrustEpoch);
        var transport = new Bt1SyncTransport(clientSide, send, receive);
        var engine = new SyncSessionEngine(
            SyncSessionRole.Dialer,
            pair.AndroidStore,
            pair.Protector,
            // ADR 0005 section 4: Bluetooth sessions are always inner protocol v1.
            PeerPair.DialerOptions() with { ProtocolVersion = 1 },
            authFailureSink: null,
            pair.Logs.CreateLogger("ClipSync.Tests.BtDialer"));
        var committed = new List<RemoteClipApplied>();
        engine.RemoteClipsCommitted += batch =>
        {
            lock (committed)
            {
                committed.AddRange(batch);
            }
        };
        var readyPeers = new List<string>();
        engine.SessionReady += peerId =>
        {
            lock (readyPeers)
            {
                readyPeers.Add(peerId);
            }
        };
        var run = engine.RunAsync(transport, CancellationToken.None);
        return new DialedSession(engine, run, committed, readyPeers);
    }

    [Fact]
    public async Task AFullBluetoothSessionSyncsClipsInBothDirections()
    {
        await using var pair = await PeerPair.CreateAsync();
        var rfcomm = new FakeRfcommServer();
        await using var host = new BluetoothSyncHost(
            pair.WindowsStore, pair.Protector, rfcomm, HostOptions(), pair.Logs);
        var sessionsChanged = 0;
        host.SessionsChanged += () => Interlocked.Increment(ref sessionsChanged);
        await host.StartAsync();

        await PeerPair.CaptureAsync(pair.AndroidStore, "经由蓝牙送达的第一条");

        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        rfcomm.EnqueueConnection(listenerSide);
        var session = await DialOverBluetoothAsync(pair, clientSide, pair.PairSecret);

        // Android -> Windows through the encrypted channel.
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.WindowsStore)).Contains("经由蓝牙送达的第一条"));
        await pair.WaitUntilAsync(() => Task.FromResult(host.ConnectedDeviceId == PeerPair.AndroidDeviceId));
        Assert.True(sessionsChanged >= 1);

        // Windows -> Android on the same live session.
        await PeerPair.CaptureAsync(pair.WindowsStore, "windows answers over rfcomm");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("windows answers over rfcomm"));

        await session.CloseQuietlyAsync();
        await pair.WaitUntilAsync(() => Task.FromResult(host.ConnectedDeviceId is null));
    }

    [Fact]
    public async Task AWrongPairSecretIsRefusedAndTheHostKeepsAccepting()
    {
        await using var pair = await PeerPair.CreateAsync();
        var rfcomm = new FakeRfcommServer();
        await using var host = new BluetoothSyncHost(
            pair.WindowsStore, pair.Protector, rfcomm, HostOptions(), pair.Logs);
        await host.StartAsync();

        var (badClient, badListener) = BluetoothTestInfrastructure.CreateDuplexPair();
        rfcomm.EnqueueConnection(badListener);
        var refusal = await Assert.ThrowsAsync<Bt1TestRefusalException>(
            () => Bt1TestClient.HandshakeAsync(
                badClient,
                RandomNumberGenerator.GetBytes(32),
                Guid.Parse(PeerPair.AndroidDeviceId),
                Guid.Parse(PeerPair.WindowsDeviceId),
                trustEpoch: 1));
        Assert.Equal(Bt1ErrorCodes.AuthFailed, refusal.Code);
        Assert.Null(host.ConnectedDeviceId);
        badClient.Dispose();

        // The listener survives the refusal: the genuine phone connects right after.
        await PeerPair.CaptureAsync(pair.AndroidStore, "第二次拨号成功");
        var (goodClient, goodListener) = BluetoothTestInfrastructure.CreateDuplexPair();
        rfcomm.EnqueueConnection(goodListener);
        var session = await DialOverBluetoothAsync(pair, goodClient, pair.PairSecret);
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.WindowsStore)).Contains("第二次拨号成功"));
        await session.CloseQuietlyAsync();
    }

    [Fact]
    public async Task StoppingTheHostEndsTheLiveSessionAndTheAcceptLoop()
    {
        await using var pair = await PeerPair.CreateAsync();
        var rfcomm = new FakeRfcommServer();
        await using var host = new BluetoothSyncHost(
            pair.WindowsStore, pair.Protector, rfcomm, HostOptions(), pair.Logs);
        await host.StartAsync();
        Assert.True(host.IsListening);

        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        rfcomm.EnqueueConnection(listenerSide);
        var session = await DialOverBluetoothAsync(pair, clientSide, pair.PairSecret);
        await pair.WaitUntilAsync(() => Task.FromResult(host.ConnectedDeviceId == PeerPair.AndroidDeviceId));

        await host.StopAsync();
        Assert.False(host.IsListening);
        Assert.Null(host.ConnectedDeviceId);
        // The dialer's engine observes the closed stream and finishes rather than hanging.
        await session.CloseQuietlyAsync();
        Assert.True(rfcomm.StopCount >= 1);
    }
}
