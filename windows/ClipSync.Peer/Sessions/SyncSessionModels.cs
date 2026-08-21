using ClipSync.Core.Protocol;

namespace ClipSync.Peer.Sessions;

public enum SyncSessionRole
{
    /// <summary>Accepted the connection; sends the challenge.</summary>
    Listener,

    /// <summary>Dialed the connection; answers the challenge.</summary>
    Dialer
}

/// <summary>Tuning knobs for one session. Defaults follow protocol v1 and the implementation plan.</summary>
public sealed record SyncSessionOptions
{
    public required string ClientVersion { get; init; }

    public string Platform { get; init; } = "windows";

    /// <summary>Dialer only: the paired device this session must authenticate as the remote end.</summary>
    public string? ExpectedPeerDeviceId { get; init; }

    public TimeSpan HandshakeTimeout { get; init; } = TimeSpan.FromSeconds(15);

    public TimeSpan ChallengeLifetime { get; init; } = TimeSpan.FromSeconds(30);

    public TimeSpan PingInterval { get; init; } = TimeSpan.FromSeconds(30);

    public int MaxMissedPings { get; init; } = 3;

    /// <summary>How often the outbox is polled for pending announcements.</summary>
    public TimeSpan OutboxDrainInterval { get; init; } = TimeSpan.FromSeconds(2);

    /// <summary>Cap on sequences requested per origin in one want_ranges message.</summary>
    public long WantSequencesPerOrigin { get; init; } = 1024;

    /// <summary>Aggregate requested sequences we accept in one incoming want_ranges before RATE_LIMITED.</summary>
    public long MaxRequestedSequencesPerMessage { get; init; } = 16384;

    public TimeProvider TimeProvider { get; init; } = TimeProvider.System;

    /// <summary>1 keeps the frozen text contract; 2 enables image_clip_v2.</summary>
    public int ProtocolVersion { get; init; } = ProtocolLimits.ProtocolVersion;

    public bool ImageClipEnabled => ProtocolVersion >= ProtocolLimits.ProtocolVersionV2;
}

/// <summary>Why the session ended; <see cref="ErrorCode"/> is a protocol code when one applies.</summary>
public sealed record SyncSessionResult(bool Authenticated, string? ErrorCode, string Detail);

/// <summary>A remote clip body that committed locally during this session.</summary>
public sealed record RemoteClipApplied(
    Guid EventId,
    string OriginDeviceId,
    long OriginSeq,
    string Content,
    DateTimeOffset CreatedAt,
    string Kind = "text",
    string? ContentHash = null,
    string? MimeType = null)
{
    public bool IsImage => string.Equals(Kind, "image", StringComparison.Ordinal);
}
