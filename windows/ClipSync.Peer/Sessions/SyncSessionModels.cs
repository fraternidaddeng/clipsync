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

    /// <summary>
    /// Inbound frames admitted per <see cref="FrameRateWindow"/> before the session is closed
    /// with a retryable RATE_LIMITED (stage-6 hardening W5). The default sustains ~340 Mbit/s
    /// of 256 KiB image chunks — far above any legitimate clipboard workload — while a
    /// tight-loop flood of small frames exhausts it within seconds.
    /// </summary>
    public int MaxFramesPerRateWindow { get; init; } = 10_000;

    /// <summary>The fixed window <see cref="MaxFramesPerRateWindow"/> is measured over.</summary>
    public TimeSpan FrameRateWindow { get; init; } = TimeSpan.FromMinutes(1);

    /// <summary>
    /// Re-checked before every outbound content announce (outbox drain) and before serving a
    /// peer's want_ranges pull, so pausing sync or turning private mode on stops outbound
    /// content immediately — mirroring the Android engine's outboundAllowed gate. Inbound
    /// stays untouched, in-flight clip_fetch replies for clips announced earlier still
    /// complete, and pending outbox entries flow again on the first drain tick after the
    /// gate reopens.
    /// </summary>
    public Func<bool> OutboundAllowed { get; init; } = static () => true;

    public TimeProvider TimeProvider { get; init; } = TimeProvider.System;

    /// <summary>1 keeps the frozen text contract; 2 enables image_clip_v2.</summary>
    public int ProtocolVersion { get; init; } = ProtocolLimits.ProtocolVersion;

    /// <summary>
    /// The local image_sync policy gate. Protocol v2 §3 allows image bodies only when both
    /// peers opted into image_clip_v2, so the listener refuses the /v2 upgrade while this is
    /// off (the dialer falls back to /v1), and a live session re-reads the gate before every
    /// inbound or outbound image so flipping the setting applies without waiting for the
    /// session to end. Text sync is never affected. The product default of the image_sync
    /// setting is ON (ADR 0004 as revised 2026-08-28, mirrored by Android's
    /// SyncSettingsStore.imageSyncEnabled), but this unwired default stays fail-closed on
    /// purpose: a host that never wires the gate has not consulted the user's setting, so it
    /// must behave like a v1 text-only peer instead of silently trading image bodies.
    /// </summary>
    public Func<bool> ImageSyncEnabled { get; init; } = static () => false;

    public bool ImageClipEnabled => ProtocolVersion >= ProtocolLimits.ProtocolVersionV2 && ImageSyncEnabled();
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
