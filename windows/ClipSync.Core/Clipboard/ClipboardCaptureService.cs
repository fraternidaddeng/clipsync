namespace ClipSync.Core.Clipboard;

public interface IClipboardEventStore
{
    ValueTask<StoredClipboardEvent> StoreAsync(
        AcceptedClipboardContent content,
        CancellationToken cancellationToken = default);
}

public interface IClipboardEventPublisher
{
    ValueTask PublishAsync(
        StoredClipboardEvent clipboardEvent,
        CancellationToken cancellationToken = default);
}

public sealed class ClipboardCaptureService(
    ClipboardCapturePolicy policy,
    IClipboardEventStore store,
    IClipboardEventPublisher? publisher = null)
{
    public async ValueTask<CaptureResult> CaptureAsync(
        ClipboardCandidate candidate,
        CancellationToken cancellationToken = default)
    {
        var decision = policy.Evaluate(candidate);
        if (decision is CaptureDecision.Reject reject)
        {
            return new CaptureResult.Rejected(reject.Reason);
        }

        var accepted = (CaptureDecision.Accept)decision;
        var stored = await store.StoreAsync(accepted.Content, cancellationToken).ConfigureAwait(false);
        if (publisher is not null)
        {
            await publisher.PublishAsync(stored, cancellationToken).ConfigureAwait(false);
        }

        return new CaptureResult.Stored(stored);
    }
}
