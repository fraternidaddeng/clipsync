using System.IO;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App.Clipboard;
using ClipSync.App.Security;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using ClipSync.Core.Sync;
using ClipSync.Peer.Security;
using ClipSync.Peer.Server;
using ClipSync.Peer.Sessions;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>ViewModel logic behind the conduit page: peer status, outbox segment, history origins.</summary>
public sealed class MainViewModelConduitTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string PeerDeviceId = "22222222-2222-4222-8222-222222222222";
    private const string UnknownDeviceId = "33333333-3333-4333-8333-333333333333";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly MainViewModel viewModel;

    public MainViewModelConduitTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-conduit-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "conduit.db"), LocalDeviceId);
        viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter);
    }

    [Fact]
    public void UpdatePeerStatusReflectsOfflineWaitingAndConnectedStates()
    {
        viewModel.UpdatePeerStatus(online: false, port: 0, connectedCount: 3);
        Assert.False(viewModel.PeerOnline);
        Assert.Equal(0, viewModel.ConnectedDeviceCount);
        Assert.Contains("未能启动", viewModel.SyncStatus, StringComparison.Ordinal);

        viewModel.UpdatePeerStatus(online: true, port: 47654, connectedCount: 0);
        Assert.True(viewModel.PeerOnline);
        Assert.Equal(47654, viewModel.PeerPort);
        Assert.Equal(0, viewModel.ConnectedDeviceCount);
        Assert.Contains("等待已配对设备连入", viewModel.SyncStatus, StringComparison.Ordinal);

        viewModel.UpdatePeerStatus(online: true, port: 47654, connectedCount: 2);
        Assert.Equal(2, viewModel.ConnectedDeviceCount);
        Assert.Contains("已连接 2 台设备", viewModel.SyncStatus, StringComparison.Ordinal);
    }

    [Fact]
    public async Task HistoryLabelsLocalRemoteAndUnknownOrigins()
    {
        await SeedDeviceAsync();
        await CaptureLocalAsync("typed on this pc");
        await StoreRemoteAsync(PeerDeviceId, "sent from phone", 1);
        await StoreRemoteAsync(UnknownDeviceId, "from a stranger", 1);

        await viewModel.InitializeAsync();

        var local = Assert.Single(viewModel.History, item => item.Text == "typed on this pc");
        Assert.False(local.IsRemote);
        Assert.Equal("本机", local.OriginLabel);

        var remote = Assert.Single(viewModel.History, item => item.Text == "sent from phone");
        Assert.True(remote.IsRemote);
        Assert.Equal("Pixel 8", remote.OriginLabel);

        var unknown = Assert.Single(viewModel.History, item => item.Text == "from a stranger");
        Assert.True(unknown.IsRemote);
        Assert.Equal("远端设备", unknown.OriginLabel);
    }

    [Fact]
    public async Task OutboxSegmentTracksQueueDepthAndLastAck()
    {
        await SeedDeviceAsync();
        await viewModel.InitializeAsync();
        Assert.Equal(0, viewModel.OutboxPendingCount);
        Assert.Equal("尚未收到对端确认", viewModel.LastAckText);

        await CaptureLocalAsync("queued for the phone");
        await viewModel.RefreshOutboxAsync();
        Assert.Equal(1, viewModel.OutboxPendingCount);

        await store.ApplyPeerAckRangesAsync(
            PeerDeviceId,
            new[] { new OriginSequenceRanges(LocalDeviceId, new[] { new SequenceRange(1, 1) }) },
            DateTimeOffset.UtcNow);
        await viewModel.RefreshOutboxAsync();
        Assert.Equal(0, viewModel.OutboxPendingCount);
        Assert.StartsWith("对端确认至", viewModel.LastAckText, StringComparison.Ordinal);
    }

    [Fact]
    public async Task RemoteActivityBumpsLastSeenAndRefreshesDevices()
    {
        await SeedDeviceAsync();
        await viewModel.InitializeAsync();
        Assert.Equal("Never connected", Assert.Single(viewModel.Devices).LastSeenText);

        await viewModel.NotifyRemoteActivityAsync([PeerDeviceId, LocalDeviceId], DateTimeOffset.UtcNow);

        Assert.StartsWith("Last seen", Assert.Single(viewModel.Devices).LastSeenText, StringComparison.Ordinal);
        var stored = await store.GetDeviceAsync(PeerDeviceId);
        Assert.NotNull(stored!.LastSeenAt);
    }

    [Fact]
    public async Task LiveSessionEventsDriveTheConduitProperties()
    {
        // A real listener endpoint over the view model's store, and a real dialed session
        // from a second "phone" store. The test consumes the same events App.xaml.cs wires
        // into MainViewModel: SessionsChanged snapshots feed UpdatePeerStatus, and
        // RemoteClipsCommitted feeds NotifyRemoteActivityAsync plus a history refresh.
        var protector = new DpapiSecretProtector();
        var secret = RandomNumberGenerator.GetBytes(32);
        var protectedSecret = Convert.ToBase64String(protector.Protect(secret));

        await store.InitializeAsync();
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PeerDeviceId, "Pixel 8", "android", CertificateFingerprint: string.Empty, protectedSecret),
            DateTimeOffset.UtcNow);

        await using var phoneStore = new SqliteClipboardEventStore(
            Path.Combine(directory, "phone.db"), PeerDeviceId);
        await phoneStore.InitializeAsync();

        using var certificate = PeerCertificate.CreateSelfSigned(
            LocalDeviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(1));
        var fingerprint = PeerCertificate.Fingerprint(certificate);
        await phoneStore.UpsertDeviceAsync(
            new NewPairedDevice(LocalDeviceId, "Windows", "windows", fingerprint, protectedSecret),
            DateTimeOffset.UtcNow);

        await using var server = new PeerServer(store, protector, new PeerServerOptions
        {
            Certificate = certificate,
            SessionOptions = new SyncSessionOptions
            {
                ClientVersion = "0.2.0",
                OutboxDrainInterval = TimeSpan.FromMilliseconds(100)
            },
            Port = 0
        });
        await server.StartAsync();

        var sessionEvents = 0;
        server.SessionsChanged += () => Interlocked.Increment(ref sessionEvents);
        var committed = new List<RemoteClipApplied>();
        server.RemoteClipsCommitted += batch =>
        {
            lock (committed)
            {
                committed.AddRange(batch);
            }
        };

        // Startup snapshot: endpoint online, nothing connected, one row queued for the phone.
        await viewModel.InitializeAsync();
        viewModel.UpdatePeerStatus(true, server.Port, server.ConnectedDeviceCount);
        await CaptureLocalAsync("queued for the phone");
        await viewModel.RefreshOutboxAsync();
        Assert.True(viewModel.PeerOnline);
        Assert.Equal(0, viewModel.ConnectedDeviceCount);
        Assert.Equal(1, viewModel.OutboxPendingCount);
        Assert.Equal("尚未收到对端确认", viewModel.LastAckText);
        Assert.Equal("Never connected", Assert.Single(viewModel.Devices).LastSeenText);

        // The phone captured something of its own, then dials in.
        var phoneBytes = Encoding.UTF8.GetBytes("sent from phone");
        await phoneStore.StoreAsync(new AcceptedClipboardContent(
            "sent from phone", Hash("sent from phone"), phoneBytes.Length, "phone-app", DateTimeOffset.UtcNow));

        var transport = await ClipSync.Peer.Client.PeerSyncClient.ConnectAsync(
            "127.0.0.1", server.Port, fingerprint, CancellationToken.None);
        using var engine = new SyncSessionEngine(
            SyncSessionRole.Dialer,
            phoneStore,
            protector,
            new SyncSessionOptions
            {
                ClientVersion = "0.2.0",
                Platform = "android",
                ExpectedPeerDeviceId = LocalDeviceId,
                OutboxDrainInterval = TimeSpan.FromMilliseconds(100)
            });
        var run = engine.RunAsync(transport, CancellationToken.None);

        try
        {
            // Session authenticated: the snapshot the SessionsChanged handler applies now
            // reports one connected device on the conduit network segment.
            await WaitUntilAsync(() => Task.FromResult(server.ConnectedDeviceCount == 1));
            Assert.True(Volatile.Read(ref sessionEvents) >= 1);
            viewModel.UpdatePeerStatus(true, server.Port, server.ConnectedDeviceCount);
            Assert.Equal(1, viewModel.ConnectedDeviceCount);
            Assert.Contains("已连接 1 台设备", viewModel.SyncStatus, StringComparison.Ordinal);

            // Convergence: our row is acked, the phone's clip committed locally.
            await WaitUntilAsync(async () => (await store.GetOutboxStatusAsync()).PendingCount == 0);
            await WaitUntilAsync(() =>
            {
                lock (committed)
                {
                    return Task.FromResult(committed.Count == 1);
                }
            });

            // What App.xaml.cs does when RemoteClipsCommitted fires.
            IReadOnlyList<RemoteClipApplied> batch;
            lock (committed)
            {
                batch = committed.ToArray();
            }

            await viewModel.NotifyRemoteActivityAsync(
                batch.Select(applied => applied.OriginDeviceId), DateTimeOffset.UtcNow);
            await viewModel.RefreshFromCaptureAsync();

            Assert.Equal(0, viewModel.OutboxPendingCount);
            Assert.StartsWith("对端确认至", viewModel.LastAckText, StringComparison.Ordinal);
            var remote = Assert.Single(viewModel.History, item => item.Text == "sent from phone");
            Assert.True(remote.IsRemote);
            Assert.Equal("Pixel 8", remote.OriginLabel);
            Assert.StartsWith("Last seen", Assert.Single(viewModel.Devices).LastSeenText, StringComparison.Ordinal);
        }
        finally
        {
            engine.RequestClose();
            await run.WaitAsync(TimeSpan.FromSeconds(10));
        }

        // Disconnect: the next snapshot returns the segment to "waiting for devices".
        await WaitUntilAsync(() => Task.FromResult(server.ConnectedDeviceCount == 0));
        viewModel.UpdatePeerStatus(true, server.Port, server.ConnectedDeviceCount);
        Assert.Equal(0, viewModel.ConnectedDeviceCount);
        Assert.Contains("等待已配对设备连入", viewModel.SyncStatus, StringComparison.Ordinal);
    }

    private static async Task WaitUntilAsync(Func<Task<bool>> condition)
    {
        var deadline = DateTimeOffset.UtcNow + TimeSpan.FromSeconds(20);
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (await condition())
            {
                return;
            }

            await Task.Delay(50);
        }

        Assert.Fail("condition not met before timeout");
    }

    private async Task SeedDeviceAsync()
    {
        await store.InitializeAsync();
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PeerDeviceId, "Pixel 8", "android", CertificateFingerprint: string.Empty, "c2VjcmV0"),
            DateTimeOffset.UtcNow);
    }

    private async Task CaptureLocalAsync(string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        await store.StoreAsync(new AcceptedClipboardContent(text, Hash(text), bytes.Length, "notepad", DateTimeOffset.UtcNow));
    }

    private async Task StoreRemoteAsync(string originDeviceId, string text, long seq)
    {
        await store.StoreRemoteEventAsync(
            new RemoteClipEvent(Guid.NewGuid(), originDeviceId, seq, text, Hash(text), "phone-app", DateTimeOffset.UtcNow, null),
            sourcePeerId: PeerDeviceId);
    }

    private static string Hash(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}
