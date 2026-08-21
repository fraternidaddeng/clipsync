namespace ClipSync.Core.Storage;

public sealed record ClipboardHistoryQuery(
    string? SearchText = null,
    int Limit = 2_000,
    int Offset = 0);

public sealed record ClipboardHistoryEntry(
    Guid EventId,
    string OriginDeviceId,
    long OriginSequence,
    string Text,
    string ContentHash,
    string? SourceProcess,
    DateTimeOffset CreatedAt,
    DateTimeOffset? ExpiresAt,
    DateTimeOffset? DeletedAt,
    string Kind = "text",
    string? MimeType = null,
    int? EncodedBytes = null,
    int? PixelWidth = null,
    int? PixelHeight = null)
{
    public bool IsDeleted => DeletedAt is not null;

    public bool IsImage => string.Equals(Kind, "image", StringComparison.Ordinal);
}

/// <summary>
/// One parsed export line ready to insert as a local-only history row.
/// </summary>
public sealed record ImportedClipboardRow(
    Guid EventId,
    string OriginDeviceId,
    long OriginSequence,
    string? Content,
    string ContentHash,
    string? SourceApp,
    DateTimeOffset CreatedAt,
    DateTimeOffset? ExpiresAt,
    string Kind = "text",
    string? MimeType = null,
    int? EncodedBytes = null,
    int? PixelWidth = null,
    int? PixelHeight = null,
    string? MediaFileName = null);

public readonly record struct ClipboardImportResult(int Imported, int Skipped);

public readonly record struct ClipboardImportParseResult(
    IReadOnlyList<ImportedClipboardRow> Rows,
    int Skipped);

public sealed class ClipboardRetentionPolicy
{
    public ClipboardRetentionPolicy(
        int maximumEntries = 2_000,
        TimeSpan? maximumAge = null)
    {
        if (maximumEntries <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maximumEntries), "The history limit must be positive.");
        }

        var age = maximumAge ?? TimeSpan.FromDays(30);
        if (age <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(maximumAge), "The retention period must be positive.");
        }

        MaximumEntries = maximumEntries;
        MaximumAge = age;
    }

    public int MaximumEntries { get; }

    public TimeSpan MaximumAge { get; }
}

public sealed record DatabaseState(
    string JournalMode,
    bool ForeignKeysEnabled,
    int SchemaVersion);

public enum StorageFaultPoint
{
    AfterSequenceAllocated,
    BeforeCommit
}

public interface IStorageFaultInjector
{
    ValueTask InjectAsync(
        StorageFaultPoint point,
        CancellationToken cancellationToken = default);
}
