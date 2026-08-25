namespace ClipSync.Core.Clipboard;

public sealed record ClipboardCandidate(
    string? Text,
    string? SourceProcess,
    DateTimeOffset CapturedAt,
    byte[]? ImageBytes = null,
    string? ImageMimeType = null,
    string? PixelDigest = null);

public sealed record CaptureSettings(
    bool IsPaused = false,
    bool IsPrivateMode = false,
    IReadOnlyCollection<string>? BlockedSourceProcesses = null,
    TimeSpan? RetentionPeriod = null,
    bool ImageSyncEnabled = false);

public enum CaptureRejectionReason
{
    EmptyText,
    TooLarge,
    Duplicate,
    SuppressedWrite,
    Paused,
    PrivateMode,
    SourceBlocked,
    UnsupportedMedia,
    DecodeFailed
}

public sealed record AcceptedClipboardContent(
    string Text,
    string ContentHash,
    int Utf8Bytes,
    string? SourceProcess,
    DateTimeOffset CapturedAt);

public sealed record AcceptedImageContent(
    byte[] EncodedBytes,
    string ContentHash,
    string MimeType,
    int PixelWidth,
    int PixelHeight,
    string? SourceProcess,
    DateTimeOffset CapturedAt,
    string? PixelDigest = null);

public abstract record CaptureDecision
{
    private CaptureDecision()
    {
    }

    public sealed record Accept(AcceptedClipboardContent Content) : CaptureDecision;

    public sealed record AcceptImage(AcceptedImageContent Image) : CaptureDecision;

    public sealed record Reject(CaptureRejectionReason Reason) : CaptureDecision;
}

public sealed record StoredClipboardEvent(Guid EventId, long OriginSequence, AcceptedClipboardContent Content);

public sealed record StoredImageEvent(Guid EventId, long OriginSequence, AcceptedImageContent Image);

public abstract record CaptureResult
{
    private CaptureResult()
    {
    }

    public sealed record Stored(StoredClipboardEvent ClipboardEvent) : CaptureResult;

    public sealed record StoredImage(StoredImageEvent ImageEvent) : CaptureResult;

    public sealed record Rejected(CaptureRejectionReason Reason) : CaptureResult;
}
