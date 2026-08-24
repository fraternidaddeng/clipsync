using ClipSync.Core.Clipboard;

namespace ClipSync.Tests.Clipboard;

public sealed class ClipboardCaptureServiceTests
{
    private static readonly string[] ExpectedStoreThenPublish = ["store", "publish"];

    [Fact]
    public async Task CapturePersistsBeforePublishing()
    {
        var calls = new List<string>();
        var store = new RecordingStore(calls);
        var publisher = new RecordingPublisher(calls);
        var service = new ClipboardCaptureService(new ClipboardCapturePolicy(), store, publisher);

        var result = await service.CaptureAsync(new("text", "notepad", DateTimeOffset.UtcNow));

        Assert.IsType<CaptureResult.Stored>(result);
        Assert.Equal(ExpectedStoreThenPublish, calls);
    }

    [Fact]
    public async Task CaptureDoesNotPublishWhenPersistenceFails()
    {
        var publisher = new RecordingPublisher(new List<string>());
        var service = new ClipboardCaptureService(
            new ClipboardCapturePolicy(),
            new ThrowingStore(),
            publisher);

        await Assert.ThrowsAsync<InvalidOperationException>(
            () => service.CaptureAsync(new("text", null, DateTimeOffset.UtcNow)).AsTask());

        Assert.Equal(0, publisher.PublishCount);
    }

    private sealed class RecordingStore(List<string> calls) : IClipboardEventStore
    {
        public ValueTask<StoredClipboardEvent> StoreAsync(AcceptedClipboardContent content, CancellationToken cancellationToken = default)
        {
            calls.Add("store");
            return ValueTask.FromResult(new StoredClipboardEvent(Guid.NewGuid(), 1, content));
        }

        public ValueTask<StoredImageEvent> StoreImageAsync(AcceptedImageContent image, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
    }

    private sealed class ThrowingStore : IClipboardEventStore
    {
        public ValueTask<StoredClipboardEvent> StoreAsync(AcceptedClipboardContent content, CancellationToken cancellationToken = default) =>
            throw new InvalidOperationException("transaction failed");

        public ValueTask<StoredImageEvent> StoreImageAsync(AcceptedImageContent image, CancellationToken cancellationToken = default) =>
            throw new InvalidOperationException("transaction failed");
    }

    private sealed class RecordingPublisher(List<string> calls) : IClipboardEventPublisher
    {
        public int PublishCount { get; private set; }

        public ValueTask PublishAsync(StoredClipboardEvent clipboardEvent, CancellationToken cancellationToken = default)
        {
            PublishCount++;
            calls.Add("publish");
            return ValueTask.CompletedTask;
        }
    }
}
