namespace ClipSync.Core.Clipboard;

public sealed record ClipboardCandidate(string? Text, string? SourceProcess, DateTimeOffset CapturedAt);

public sealed record CaptureSettings(
    bool IsPaused = false,
    bool IsPrivateMode = false,
    IReadOnlyCollection<string>? BlockedSourceProcesses = null,
    TimeSpan? RetentionPeriod = null);

public enum CaptureRejectionReason
{
    EmptyText,
    TooLarge,
    Duplicate,
    SuppressedWrite,
    Paused,
    PrivateMode,
    SourceBlocked
}

public sealed record AcceptedClipboardContent(
    string Text,
    string ContentHash,
    int Utf8Bytes,
    string? SourceProcess,
    DateTimeOffset CapturedAt);

public abstract record CaptureDecision
{
    private CaptureDecision()
    {
    }

    public sealed record Accept(AcceptedClipboardContent Content) : CaptureDecision;

    public sealed record Reject(CaptureRejectionReason Reason) : CaptureDecision;
}

public sealed record StoredClipboardEvent(Guid EventId, long OriginSequence, AcceptedClipboardContent Content);

public abstract record CaptureResult
{
    private CaptureResult()
    {
    }

    public sealed record Stored(StoredClipboardEvent ClipboardEvent) : CaptureResult;

    public sealed record Rejected(CaptureRejectionReason Reason) : CaptureResult;
}
