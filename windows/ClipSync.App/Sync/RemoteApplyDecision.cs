using ClipSync.Peer.Sessions;

namespace ClipSync.App.Sync;

/// <summary>
/// The pure policy behind App.OnRemoteClipsCommitted: which clip of one committed batch — if
/// any — reaches the system clipboard. Mirrors the Android side exactly (InboxDelivery's
/// autoApplyAllowed gate plus the newest-only rule proven by WindowsAndroidSyncChainTest):
/// paused sync still receives into history but never applies; only the newest body of a batch
/// is considered (never an older fallback); text obeys 自动写入剪贴板 while images have their
/// own opt-in 自动写入远端图片 gate (ADR 0004) and require a content hash to load bytes from.
/// </summary>
public abstract record RemoteApplyDecision
{
    private RemoteApplyDecision()
    {
    }

    /// <summary>Nothing is written to the clipboard; history and notifications are unaffected.</summary>
    public sealed record None : RemoteApplyDecision
    {
        public static readonly None Instance = new();
    }

    /// <summary>Write <see cref="Clip"/>'s text body, with the loopback suppression window armed first.</summary>
    public sealed record ApplyText(RemoteClipApplied Clip) : RemoteApplyDecision;

    /// <summary>Write <see cref="Clip"/>'s image bytes (loaded via its content hash), suppression armed first.</summary>
    public sealed record ApplyImage(RemoteClipApplied Clip) : RemoteApplyDecision;

    public static RemoteApplyDecision Decide(
        IReadOnlyList<RemoteClipApplied> batch,
        bool isPaused,
        bool autoApplyRemote,
        bool autoApplyImages)
    {
        if (batch.Count == 0 || isPaused)
        {
            return None.Instance;
        }

        var latest = batch[^1];
        if (latest.IsImage)
        {
            // Text's AutoApplyRemote never writes pixel bytes on its own, and a closed image
            // gate never falls back to an older text clip: newest-only is strict.
            return autoApplyImages && latest.ContentHash is not null
                ? new ApplyImage(latest)
                : None.Instance;
        }

        return autoApplyRemote ? new ApplyText(latest) : None.Instance;
    }
}
