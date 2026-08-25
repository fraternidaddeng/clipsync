using ClipSync.App.Sync;
using ClipSync.Peer.Sessions;

namespace ClipSync.App.Tests.Sync;

/// <summary>
/// The Windows mirror of the auto-apply scenarios WindowsAndroidSyncChainTest proves on the
/// Android side: paused sync still receives but never applies, only the newest body of a
/// batch is considered, text and image writes obey their independent gates, and a closed
/// image gate never falls back to an older text clip.
/// </summary>
public sealed class RemoteApplyDecisionTests
{
    [Fact]
    public void OnlyTheNewestClipOfABatchIsApplied()
    {
        var batch = new[] { Text("first body"), Text("second body") };

        var decision = RemoteApplyDecision.Decide(batch, isPaused: false, autoApplyRemote: true, autoApplyImages: false);

        var apply = Assert.IsType<RemoteApplyDecision.ApplyText>(decision);
        Assert.Equal("second body", apply.Clip.Content);
    }

    [Fact]
    public void PausedSyncStillReceivesButNeverApplies()
    {
        var decision = RemoteApplyDecision.Decide(
            [Text("received while paused")],
            isPaused: true,
            autoApplyRemote: true,
            autoApplyImages: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void AutoApplyOffKeepsTextOffTheClipboard()
    {
        var decision = RemoteApplyDecision.Decide(
            [Text("manual copy only")],
            isPaused: false,
            autoApplyRemote: false,
            autoApplyImages: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void NewestImageAppliesOnlyThroughItsOwnGate()
    {
        var batch = new[] { Text("older text"), Image("aabb") };

        var applied = RemoteApplyDecision.Decide(batch, isPaused: false, autoApplyRemote: false, autoApplyImages: true);
        var apply = Assert.IsType<RemoteApplyDecision.ApplyImage>(applied);
        Assert.Equal("aabb", apply.Clip.ContentHash);

        // Text's gate never writes pixel bytes on its own (ADR 0004).
        var textGateOnly = RemoteApplyDecision.Decide(batch, isPaused: false, autoApplyRemote: true, autoApplyImages: false);
        Assert.IsType<RemoteApplyDecision.None>(textGateOnly);
    }

    [Fact]
    public void ClosedImageGateNeverFallsBackToAnOlderTextClip()
    {
        // Newest-only is strict: the batch's older text body must not sneak onto the
        // clipboard just because the newest clip (an image) cannot be applied.
        var decision = RemoteApplyDecision.Decide(
            [Text("older text"), Image("ccdd")],
            isPaused: false,
            autoApplyRemote: true,
            autoApplyImages: false);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void ImageWithoutAContentHashNeverApplies()
    {
        var headerless = Text("placeholder") with { Kind = "image", ContentHash = null };

        var decision = RemoteApplyDecision.Decide(
            [headerless],
            isPaused: false,
            autoApplyRemote: true,
            autoApplyImages: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

    [Fact]
    public void EmptyBatchDecidesNone()
    {
        var decision = RemoteApplyDecision.Decide(
            [],
            isPaused: false,
            autoApplyRemote: true,
            autoApplyImages: true);

        Assert.IsType<RemoteApplyDecision.None>(decision);
    }

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
