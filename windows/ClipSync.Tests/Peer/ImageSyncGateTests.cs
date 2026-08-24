using System.Net.WebSockets;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Peer.Client;

namespace ClipSync.Tests.Peer;

/// <summary>
/// Strict-audit finding #4: the Windows image_sync setting must govern inbound image
/// acceptance on the listener, not only capture. Protocol v2 §3 allows image bodies only
/// when both peers opted into image_clip_v2, so a listener with the setting off behaves
/// like a v1-only peer (route refusal → the dialer falls back to /v1), and a live v2
/// session re-reads the gate before every image in either direction.
/// </summary>
public sealed class ImageSyncGateTests
{
    [Fact]
    public async Task ListenerRefusesV2UpgradeWhileImageSyncIsOffAndV1TextStillFlows()
    {
        await using var pair = await PeerPair.CreateAsync(
            serverSessionOptions: PeerPair.DefaultSessionOptions() with { ImageSyncEnabled = static () => false });

        // The capability is not advertised: the /v2 route is refused before the upgrade,
        // which is exactly the signal that makes the Android dialer fall back to /v1.
        await Assert.ThrowsAsync<WebSocketException>(async () =>
            await PeerSyncClient.ConnectAsync(
                "127.0.0.1",
                pair.Server.Port,
                pair.ServerFingerprint,
                protocolVersion: 2,
                CancellationToken.None));

        // Text sync on the frozen v1 contract is unaffected by the image gate.
        await PeerPair.CaptureAsync(pair.WindowsStore, "text-still-flows");
        var session = await pair.DialAsync(PeerPair.DialerOptions() with { ProtocolVersion = 1 });
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("text-still-flows"));
        var result = await session.CloseAsync();
        Assert.True(result.Authenticated);
    }

    [Fact]
    public async Task InboundImageOnLiveV2SessionIsRefusedAfterTheSettingTurnsOff()
    {
        var imageSyncEnabled = true;
        await using var pair = await PeerPair.CreateAsync(
            serverSessionOptions: PeerPair.DefaultSessionOptions() with
            {
                ImageSyncEnabled = () => Volatile.Read(ref imageSyncEnabled)
            });

        var session = await pair.DialAsync();
        await PeerPair.CaptureAsync(pair.AndroidStore, "session-warmup");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.WindowsStore)).Contains("session-warmup"));

        // The user turns 图片同步 off while the v2 session is still open; the next inbound
        // image announce must be refused instead of committed into history.
        Volatile.Write(ref imageSyncEnabled, false);
        var png = ImageCodec.EncodePngBgra(2, 1, [255, 0, 0, 255, 0, 255, 0, 255]);
        var hash = ImageCodec.HashBytes(png);
        await PeerPair.CaptureImageAsync(pair.AndroidStore, png, hash, "image/png", width: 2, height: 1);

        var result = await session.Run.WaitAsync(TimeSpan.FromSeconds(20));
        Assert.Equal(ProtocolErrorCodes.UnsupportedMedia, result.ErrorCode);

        // The listener refused for the right reason and committed no image event.
        // (Blob storage cannot be asserted here: both test stores share one
        // content-addressed media folder, and the sender committed the blob locally.)
        Assert.Contains(pair.Logs.Lines, line => line.Contains("image_sync_disabled", StringComparison.Ordinal));
        var items = await pair.WindowsStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
        Assert.DoesNotContain(items, item => item.IsImage);
    }

    [Fact]
    public async Task OutboundImageDowngradesToLocalOnlyMarkerAfterTheSettingTurnsOff()
    {
        var imageSyncEnabled = true;
        await using var pair = await PeerPair.CreateAsync(
            serverSessionOptions: PeerPair.DefaultSessionOptions() with
            {
                ImageSyncEnabled = () => Volatile.Read(ref imageSyncEnabled)
            });

        var session = await pair.DialAsync();
        await PeerPair.CaptureAsync(pair.WindowsStore, "session-warmup");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("session-warmup"));

        // With the gate off, an image captured on the listener travels as a local_only
        // unavailable marker: the origin cursor still advances, no bytes cross the wire.
        Volatile.Write(ref imageSyncEnabled, false);
        var png = ImageCodec.EncodePngBgra(2, 1, [255, 0, 0, 255, 0, 255, 0, 255]);
        var hash = ImageCodec.HashBytes(png);
        var stored = await PeerPair.CaptureImageAsync(pair.WindowsStore, png, hash, "image/png", width: 2, height: 1);

        await pair.WaitUntilAsync(async () =>
        {
            var vector = await pair.AndroidStore.GetKnownVectorAsync();
            return vector.TryGetValue(PeerPair.WindowsDeviceId, out var state)
                && state.ContiguousSeq >= stored.OriginSequence;
        });

        // The dialer holds a terminal marker, not an image event; the session survived.
        // (Blob storage cannot be asserted: the test stores share one media folder.)
        var items = await pair.AndroidStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
        Assert.DoesNotContain(items, item => item.IsImage);

        var result = await session.CloseAsync();
        Assert.True(result.Authenticated);
    }
}
