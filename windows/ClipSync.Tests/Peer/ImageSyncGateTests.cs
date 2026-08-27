using System.Net.WebSockets;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Peer.Client;
using ClipSync.Peer.Sessions;

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
    public void ImageSyncGateDefaultsOffMatchingTheAndroidDefault()
    {
        // Manual-QA limitation #5 (2026-08-25): the two platforms must default the same.
        // Image sync is opt-in everywhere (ADR 0004 / DESIGN-CHARTER §5.9): Android's
        // SyncSettingsStore.imageSyncEnabled defaults to false, so an unwired Windows
        // session-options gate must fail closed too — even on a /v2 route.
        var options = new SyncSessionOptions { ClientVersion = "0.2.0" };
        Assert.False(options.ImageSyncEnabled());
        Assert.False(options.ImageClipEnabled);
        Assert.False((options with { ProtocolVersion = ProtocolLimits.ProtocolVersionV2 }).ImageClipEnabled);
    }

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

        // ADR 0005 §5: the origin's history row now carries the 仅本机保留 mark —
        // the peer's cursor moved past the image and it will never be retransmitted.
        var originEntry = await pair.WindowsStore.GetByIdAsync(stored.EventId);
        Assert.NotNull(originEntry);
        Assert.True(originEntry.IsLocalOnly);

        var result = await session.CloseAsync();
        Assert.True(result.Authenticated);
    }

    [Fact]
    public async Task BouncingSessionsAfterEnablingTheGateLetsTheRedialUpgradeToV2AndImagesFlow()
    {
        // The session-stickiness regression behind「图片传输死掉了」: the wire version is
        // chosen when the phone dials, so a session established while 图片同步 was off runs
        // text-only v1 forever — flipping the toggle later changes nothing until the session
        // happens to drop, which a stable network may never do. The fix bounces every live
        // session on the toggle (App: MainViewModel.ImageSyncEnabledChanged →
        // PeerSyncHost.DisconnectAllSessions; Android: ClipboardSyncService →
        // SyncSupervisor.restartSession), and the redial renegotiates v2.
        var imageSyncEnabled = false;
        await using var pair = await PeerPair.CreateAsync(
            serverSessionOptions: PeerPair.DefaultSessionOptions() with
            {
                ImageSyncEnabled = () => Volatile.Read(ref imageSyncEnabled)
            });

        // With the gate off, the phone can only hold a text-only v1 session.
        var v1 = await pair.DialAsync(PeerPair.DialerOptions() with { ProtocolVersion = 1 });
        await PeerPair.CaptureAsync(pair.WindowsStore, "v1-warmup");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("v1-warmup"));

        // The user turns 图片同步 on; the app layer bounces the live sessions in response.
        Volatile.Write(ref imageSyncEnabled, true);
        pair.Server.DisconnectAllSessions();
        var v1Result = await v1.Run.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(v1Result.Authenticated);

        // The phone's reconnect loop redials within a second, now succeeding on /v2.
        var v2 = await pair.DialAsync();

        // Windows → Android: a fresh image commits on the dialer as a real image event.
        var toPhone = ImageCodec.EncodePngBgra(2, 1, [255, 0, 0, 255, 0, 255, 0, 255]);
        var toPhoneHash = ImageCodec.HashBytes(toPhone);
        await PeerPair.CaptureImageAsync(pair.WindowsStore, toPhone, toPhoneHash, "image/png", width: 2, height: 1);
        await pair.WaitUntilAsync(async () =>
        {
            var items = await pair.AndroidStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
            return items.Any(item => item.IsImage && item.ContentHash == toPhoneHash);
        });

        // Android → Windows on the same renegotiated session.
        var toDesktop = ImageCodec.EncodePngBgra(1, 2, [0, 0, 255, 255, 255, 255, 0, 255]);
        var toDesktopHash = ImageCodec.HashBytes(toDesktop);
        await PeerPair.CaptureImageAsync(pair.AndroidStore, toDesktop, toDesktopHash, "image/png", width: 1, height: 2);
        await pair.WaitUntilAsync(async () =>
        {
            var items = await pair.WindowsStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
            return items.Any(item => item.IsImage && item.ContentHash == toDesktopHash);
        });

        Assert.True((await v2.CloseAsync()).Authenticated);
    }

    [Fact]
    public async Task V1SessionMarksOriginImageLocalOnlyAndALaterV2SessionNeverBackfillsIt()
    {
        await using var pair = await PeerPair.CreateAsync();

        // The Bluetooth-window shape: both image gates are on, but the session runs
        // protocol v1 (bt1 sessions never declare image_clip_v2 — ADR 0005 §4), so a
        // captured image travels as a local_only terminal marker.
        var v1 = await pair.DialAsync(PeerPair.DialerOptions() with { ProtocolVersion = 1 });
        // The listener raises this exactly when a mark actually changed in the store —
        // it is the App's cue to refresh the open history window (no manual refresh).
        var markSignals = 0;
        pair.Server.LocalOnlyMarksChanged += () => Interlocked.Increment(ref markSignals);
        await PeerPair.CaptureAsync(pair.WindowsStore, "v1-warmup");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("v1-warmup"));

        var png = ImageCodec.EncodePngBgra(2, 1, [255, 0, 0, 255, 0, 255, 0, 255]);
        var hash = ImageCodec.HashBytes(png);
        var image = await PeerPair.CaptureImageAsync(pair.WindowsStore, png, hash, "image/png", width: 2, height: 1);
        await pair.WaitUntilAsync(async () =>
        {
            var vector = await pair.AndroidStore.GetKnownVectorAsync();
            return vector.TryGetValue(PeerPair.WindowsDeviceId, out var state)
                && state.ContiguousSeq >= image.OriginSequence;
        });

        // The origin's history annotates the image 仅本机保留; the text before it is untouched.
        // The downgraded announce also raised the refresh signal (text-only announces did not).
        await pair.WaitUntilAsync(() => Task.FromResult(Volatile.Read(ref markSignals) == 1));
        Assert.True((await pair.WindowsStore.GetByIdAsync(image.EventId))!.IsLocalOnly);
        var windowsHistory = await pair.WindowsStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
        Assert.All(windowsHistory.Where(item => !item.IsImage), item => Assert.False(item.IsLocalOnly));

        // A text after the image still flows: the marker closed the sequence gap.
        await PeerPair.CaptureAsync(pair.WindowsStore, "text-after-image");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("text-after-image"));
        Assert.True((await v1.CloseAsync()).Authenticated);

        // IP recovery: an image-capable v2 session syncs new text but never backfills
        // the terminated image (origin-authoritative, irreversible), so the mark stays.
        var v2 = await pair.DialAsync();
        await PeerPair.CaptureAsync(pair.WindowsStore, "post-recovery");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("post-recovery"));

        var androidItems = await pair.AndroidStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
        Assert.DoesNotContain(androidItems, item => item.IsImage);
        Assert.True((await pair.WindowsStore.GetByIdAsync(image.EventId))!.IsLocalOnly);
        Assert.True((await v2.CloseAsync()).Authenticated);
    }
}
