using ClipSync.Core.Sync;

namespace ClipSync.Core.Storage;

public sealed record PairedDevice(
    string DeviceId,
    string DisplayName,
    string Platform,
    string CertificateFingerprint,
    string PairSecretProtected,
    long TrustEpoch,
    DateTimeOffset CreatedAt,
    DateTimeOffset? LastSeenAt,
    DateTimeOffset? RevokedAt)
{
    public bool IsRevoked => RevokedAt is not null;
}

public sealed record NewPairedDevice(
    string DeviceId,
    string DisplayName,
    string Platform,
    string CertificateFingerprint,
    string PairSecretProtected);

/// <summary>A remote clip event body received from a peer, already protocol-validated.</summary>
public sealed record RemoteClipEvent(
    Guid EventId,
    string OriginDeviceId,
    long OriginSeq,
    string Content,
    string ContentHash,
    string? SourceApp,
    DateTimeOffset CreatedAt,
    DateTimeOffset? ExpiresAt);

/// <summary>An origin-authoritative unavailable marker; advances cursors without content.</summary>
public sealed record RemoteTerminalMarker(
    Guid EventId,
    string OriginDeviceId,
    long OriginSeq,
    string Reason);

public abstract record RemoteStoreResult
{
    private RemoteStoreResult()
    {
    }

    /// <summary>The event committed; <paramref name="ReceiveState"/> is the origin state after the transaction.</summary>
    public sealed record Stored(OriginReceiveState ReceiveState) : RemoteStoreResult;

    /// <summary>The identical event or marker was already persisted; success for idempotent retries.</summary>
    public sealed record AlreadyPersisted : RemoteStoreResult;

    /// <summary>The idempotency key maps to a different identity; the protocol treats this as fatal.</summary>
    public sealed record IdentityConflict(string Detail) : RemoteStoreResult;
}

public static class OutboxEntryStates
{
    public const string Pending = "pending";
    public const string Announced = "announced";
}

public sealed record OutboxEntry(
    long Id,
    string PeerId,
    Guid EventId,
    string OriginDeviceId,
    long OriginSeq,
    string State,
    int Attempts);

/// <summary>A clips row projected for sync: either an available body or a terminal marker.</summary>
public sealed record SyncableClipEvent(
    Guid EventId,
    string OriginDeviceId,
    long OriginSeq,
    string? Content,
    string? ContentHash,
    string? SourceApp,
    DateTimeOffset CreatedAt,
    DateTimeOffset? ExpiresAt,
    string? TerminalReason)
{
    public bool IsTerminal => TerminalReason is not null;
}
