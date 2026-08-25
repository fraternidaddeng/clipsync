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
    int? PixelHeight = null,
    DateTimeOffset? LocalOnlyAt = null)
{
    public bool IsDeleted => DeletedAt is not null;

    public bool IsImage => string.Equals(Kind, "image", StringComparison.Ordinal);

    /// <summary>
    /// True when this local image was delivered to the peer as a `local_only` terminal
    /// marker (ADR 0005 §4: a text-only session — Bluetooth fallback or the image gate
    /// off — advanced the peer's cursor past it). It stays usable here but will never
    /// sync, so history annotates it 仅本机保留 (ADR 0005 §5).
    /// </summary>
    public bool IsLocalOnly => LocalOnlyAt is not null;
}

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
    BeforeCommit,

    /// <summary>
    /// Fires between a successful commit and post-commit maintenance (blob collection).
    /// A failure injected here must surface unmasked and must never roll back the
    /// already-committed transaction.
    /// </summary>
    AfterCommit
}

public interface IStorageFaultInjector
{
    ValueTask InjectAsync(
        StorageFaultPoint point,
        CancellationToken cancellationToken = default);
}
