using ClipSync.App.Sync;
using ClipSync.Peer.Sessions;

namespace ClipSync.App.Tests.Sync;

/// <summary>
/// The Windows mirror of the auto-apply scenarios WindowsAndroidSyncChainTest proves on the
/// Android side: paused sync still receives but never applies, only the newest body of a
/// batch is considered, text and image writes obey their independent gates, and a closed
/// image gate never falls back to an older text clip. Windows-only addition: 私密模式
/// (which promises 捕获与同步全部停止) stops the automatic write like pause does.
/// </summary>
public sealed class RemoteApplyDecisionTests
{
    [Fact]
    public void OnlyTheNewestClipOfABatchIsApplied()
    {
        var batch = new[] { Text("first body"), Text("second body") };

        var decision = Decide(batch, autoApplyRemote: true, autoApplyImages: false);

        var apply = Assert.IsType<RemoteApplyDecision.ApplyText>(decision);
        Assert.Equal("second body", apply.Clip.Content);
    }

    [Fact]
    public void PausedSyncStillReceivesButNeverApplies()
    {
        var decision = Decide(
            [Text("received while paused")],
            autoApplyRemote: true,
            autoApplyImages: true,
            isPaused: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void PrivateModeStopsTextAndImageWritesLikePause()
    {
        // 私密模式 promises 捕获与同步全部停止, and the tray ranks it above pause; letting a
        // remote clip overwrite the local clipboard mid-私密 would break both. Receiving into
        // history is untouched — only the automatic write is stopped, exactly like pause.
        var text = Decide(
            [Text("received while private")],
            autoApplyRemote: true,
            autoApplyImages: true,
            isPrivateMode: true);
        Assert.IsType<RemoteApplyDecision.None>(text);

        var image = Decide(
            [Image("eeff")],
            autoApplyRemote: true,
            autoApplyImages: true,
            isPrivateMode: true);
        Assert.IsType<RemoteApplyDecision.None>(image);
    }

    [Fact]
    public void AutoApplyOffKeepsTextOffTheClipboard()
    {
        var decision = Decide(
            [Text("manual copy only")],
            autoApplyRemote: false,
            autoApplyImages: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void NewestImageAppliesOnlyThroughItsOwnGate()
    {
        var batch = new[] { Text("older text"), Image("aabb") };

        var applied = Decide(batch, autoApplyRemote: false, autoApplyImages: true);
        var apply = Assert.IsType<RemoteApplyDecision.ApplyImage>(applied);
        Assert.Equal("aabb", apply.Clip.ContentHash);

        // Text's gate never writes pixel bytes on its own (ADR 0004).
        var textGateOnly = Decide(batch, autoApplyRemote: true, autoApplyImages: false);
        Assert.IsType<RemoteApplyDecision.None>(textGateOnly);
    }

    [Fact]
    public void ClosedImageGateNeverFallsBackToAnOlderTextClip()
    {
        // Newest-only is strict: the batch's older text body must not sneak onto the
        // clipboard just because the newest clip (an image) cannot be applied.
        var decision = Decide(
            [Text("older text"), Image("ccdd")],
            autoApplyRemote: true,
            autoApplyImages: false);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void ImageWithoutAContentHashNeverApplies()
    {
        var headerless = Text("placeholder") with { Kind = "image", ContentHash = null };

        var decision = Decide(
            [headerless],
            autoApplyRemote: true,
            autoApplyImages: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void EmptyBatchDecidesNone()
    {
        var decision = Decide(
            [],
            autoApplyRemote: true,
            autoApplyImages: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    private static RemoteApplyDecision Decide(
        IReadOnlyList<RemoteClipApplied> batch,
        bool autoApplyRemote,
        bool autoApplyImages,
        bool isPaused = false,
        bool isPrivateMode = false) =>
        RemoteApplyDecision.Decide(batch, isPaused, isPrivateMode, autoApplyRemote, autoApplyImages);

    private static RemoteClipApplied Text(string content) =>
        new(
            Guid.NewGuid(),
            OriginDeviceId: "11111111-1111-4111-8111-111111111111",
            OriginSeq: 1,
            Content: content,
            CreatedAt: DateTimeOffset.UtcNow);

    private static RemoteClipApplied Image(string contentHash) =>
        Text(string.Empty) with { Kind = "image", ContentHash = contentHash, MimeType = "image/png" };
}
