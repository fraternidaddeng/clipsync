using System.Text.Json.Serialization;

namespace ClipSync.Core.Protocol;

public static class ProtocolMessageTypes
{
    public const string Hello = "hello";
    public const string Challenge = "challenge";
    public const string Auth = "auth";
    public const string KnownVector = "known_vector";
    public const string WantRanges = "want_ranges";
    public const string ClipAnnounce = "clip_announce";
    public const string ClipFetch = "clip_fetch";
    public const string ClipPayload = "clip_payload";
    public const string ClipPayloadBegin = "clip_payload_begin";
    public const string ClipPayloadChunk = "clip_payload_chunk";
    public const string ClipPayloadEnd = "clip_payload_end";
    public const string AckRanges = "ack_ranges";
    public const string Error = "error";
    public const string Ping = "ping";
    public const string Pong = "pong";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.Ordinal)
    {
        Hello, Challenge, Auth, KnownVector, WantRanges, ClipAnnounce,
        ClipFetch, ClipPayload, ClipPayloadBegin, ClipPayloadChunk, ClipPayloadEnd,
        AckRanges, Error, Ping, Pong
    };

    public static readonly IReadOnlySet<string> V1 = new HashSet<string>(StringComparer.Ordinal)
    {
        Hello, Challenge, Auth, KnownVector, WantRanges, ClipAnnounce,
        ClipFetch, ClipPayload, AckRanges, Error, Ping, Pong
    };
}

public static class ProtocolLimits
{
    public const int ProtocolVersion = 1;
    public const int ProtocolVersionV2 = 2;
    public const int MaxJsonDepth = 16;
    public const int MaxContentUtf8Bytes = 1_048_576;
    public const int MaxPayloadBatchContentBytes = 1_048_576;
    public const int MaxAnnounceClips = 256;
    public const int MaxPayloadClips = 32;
    public const int MaxFetchEventIds = 128;
    public const int MaxOriginsPerMessage = 128;
    public const int MaxRangesPerOrigin = 256;
    public const int MaxWebSocketTextMessageBytes = 7 * 1_048_576;
    public const int MaxSourceAppLength = 256;
    public const int MaxClientVersionLength = 64;
    public const long MaxRetryAfterMs = 300_000;
    public const int MaxCapabilities = 16;
    public const int MaxEncodedImageBytes = 16 * 1024 * 1024;
    public const int MaxImagePixels = 32 * 1024 * 1024;
    public const int MaxImageSide = 8_192;
    public const int MaxChunkBytes = 256 * 1024;
    public const int MaxChunkCount = 64;
    public const int MaxConcurrentImageDownloads = 2;
    public const string CapabilityImageClipV2 = "image_clip_v2";
}

public static class ProtocolErrorCodes
{
    public const string MalformedJson = "MALFORMED_JSON";
    public const string SchemaViolation = "SCHEMA_VIOLATION";
    public const string UnsupportedVersion = "UNSUPPORTED_VERSION";
    public const string AuthRequired = "AUTH_REQUIRED";
    public const string AuthFailed = "AUTH_FAILED";
    public const string ChallengeExpired = "CHALLENGE_EXPIRED";
    public const string ReplayDetected = "REPLAY_DETECTED";
    public const string DeviceRevoked = "DEVICE_REVOKED";
    public const string TrustEpochMismatch = "TRUST_EPOCH_MISMATCH";
    public const string MessageOutOfOrder = "MESSAGE_OUT_OF_ORDER";
    public const string InvalidRange = "INVALID_RANGE";
    public const string EventConflict = "EVENT_CONFLICT";
    public const string PayloadNotFound = "PAYLOAD_NOT_FOUND";
    public const string HashMismatch = "HASH_MISMATCH";
    public const string PayloadTooLarge = "PAYLOAD_TOO_LARGE";
    public const string RateLimited = "RATE_LIMITED";
    public const string InternalError = "INTERNAL_ERROR";
    public const string UnsupportedMedia = "UNSUPPORTED_MEDIA";
    public const string MediaTooLarge = "MEDIA_TOO_LARGE";
    public const string MediaDecodeFailed = "MEDIA_DECODE_FAILED";
    public const string MediaHashMismatch = "MEDIA_HASH_MISMATCH";
    public const string MediaOutOfOrder = "MEDIA_OUT_OF_ORDER";
    public const string MediaStorageFailed = "MEDIA_STORAGE_FAILED";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.Ordinal)
    {
        MalformedJson, SchemaViolation, UnsupportedVersion, AuthRequired, AuthFailed,
        ChallengeExpired, ReplayDetected, DeviceRevoked, TrustEpochMismatch, MessageOutOfOrder,
        InvalidRange, EventConflict, PayloadNotFound, HashMismatch, PayloadTooLarge,
        RateLimited, InternalError
    };

    public static readonly IReadOnlySet<string> V2 = new HashSet<string>(StringComparer.Ordinal)
    {
        MalformedJson, SchemaViolation, UnsupportedVersion, AuthRequired, AuthFailed,
        ChallengeExpired, ReplayDetected, DeviceRevoked, TrustEpochMismatch, MessageOutOfOrder,
        InvalidRange, EventConflict, PayloadNotFound, HashMismatch, PayloadTooLarge,
        RateLimited, InternalError, UnsupportedMedia, MediaTooLarge, MediaDecodeFailed,
        MediaHashMismatch, MediaOutOfOrder, MediaStorageFailed
    };
}

public sealed record RangeDto
{
    [JsonPropertyName("start_seq")]
    public required long StartSeq { get; init; }

    [JsonPropertyName("end_seq")]
    public required long EndSeq { get; init; }
}

public sealed record OriginStateDto
{
    [JsonPropertyName("origin_device_id")]
    public required string OriginDeviceId { get; init; }

    [JsonPropertyName("contiguous_seq")]
    public required long ContiguousSeq { get; init; }

    [JsonPropertyName("received_ranges")]
    public IReadOnlyList<RangeDto>? ReceivedRanges { get; init; }
}

public sealed record SyncStateDto
{
    [JsonPropertyName("origins")]
    public required IReadOnlyList<OriginStateDto> Origins { get; init; }
}

public sealed record HelloBody
{
    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("platform")]
    public required string Platform { get; init; }

    [JsonPropertyName("client_version")]
    public required string ClientVersion { get; init; }

    [JsonPropertyName("trust_epoch")]
    public required long TrustEpoch { get; init; }

    [JsonPropertyName("known_vector")]
    public required SyncStateDto KnownVector { get; init; }

    [JsonPropertyName("capabilities")]
    public IReadOnlyList<string>? Capabilities { get; init; }
}

public sealed record ChallengeBody
{
    [JsonPropertyName("algorithm")]
    public required string Algorithm { get; init; }

    [JsonPropertyName("nonce")]
    public required string Nonce { get; init; }

    [JsonPropertyName("challenger_device_id")]
    public required string ChallengerDeviceId { get; init; }

    [JsonPropertyName("responder_device_id")]
    public required string ResponderDeviceId { get; init; }

    [JsonPropertyName("trust_epoch")]
    public required long TrustEpoch { get; init; }

    [JsonPropertyName("expires_at_ms")]
    public required long ExpiresAtMs { get; init; }
}

public sealed record AuthBody
{
    [JsonPropertyName("algorithm")]
    public required string Algorithm { get; init; }

    [JsonPropertyName("challenge_request_id")]
    public required string ChallengeRequestId { get; init; }

    [JsonPropertyName("responder_device_id")]
    public required string ResponderDeviceId { get; init; }

    [JsonPropertyName("trust_epoch")]
    public required long TrustEpoch { get; init; }

    [JsonPropertyName("proof")]
    public required string Proof { get; init; }
}

public sealed record OriginRangesDto
{
    [JsonPropertyName("origin_device_id")]
    public required string OriginDeviceId { get; init; }

    [JsonPropertyName("ranges")]
    public required IReadOnlyList<RangeDto> Ranges { get; init; }
}

public sealed record WantRangesBody
{
    [JsonPropertyName("requests")]
    public required IReadOnlyList<OriginRangesDto> Requests { get; init; }
}

public static class ClipAvailability
{
    public const string Available = "available";
    public const string Unavailable = "unavailable";
}

public static class ClipUnavailableReasons
{
    public const string LocalOnly = "local_only";
    public const string Deleted = "deleted";
    public const string Expired = "expired";
    public const string PolicyFiltered = "policy_filtered";
    public const string NotFound = "not_found";

    public const string UnsupportedMedia = "unsupported_media";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.Ordinal)
    {
        LocalOnly, Deleted, Expired, PolicyFiltered, NotFound
    };

    public static readonly IReadOnlySet<string> V2 = new HashSet<string>(StringComparer.Ordinal)
    {
        LocalOnly, Deleted, Expired, PolicyFiltered, NotFound, UnsupportedMedia
    };
}

public sealed record ClipHeaderDto
{
    [JsonPropertyName("event_id")]
    public required string EventId { get; init; }

    [JsonPropertyName("origin_device_id")]
    public required string OriginDeviceId { get; init; }

    [JsonPropertyName("origin_seq")]
    public required long OriginSeq { get; init; }

    [JsonPropertyName("availability")]
    public required string Availability { get; init; }

    [JsonPropertyName("kind")]
    public string? Kind { get; init; }

    [JsonPropertyName("content_hash")]
    public string? ContentHash { get; init; }

    [JsonPropertyName("utf8_bytes")]
    public long? Utf8Bytes { get; init; }

    [JsonPropertyName("source_app")]
    public string? SourceApp { get; init; }

    [JsonPropertyName("created_at_ms")]
    public long? CreatedAtMs { get; init; }

    [JsonPropertyName("expires_at_ms")]
    public long? ExpiresAtMs { get; init; }

    [JsonPropertyName("reason")]
    public string? Reason { get; init; }

    [JsonPropertyName("mime_type")]
    public string? MimeType { get; init; }

    [JsonPropertyName("encoded_bytes")]
    public long? EncodedBytes { get; init; }

    [JsonPropertyName("pixel_width")]
    public long? PixelWidth { get; init; }

    [JsonPropertyName("pixel_height")]
    public long? PixelHeight { get; init; }
}

public sealed record ClipAnnounceBody
{
    [JsonPropertyName("clips")]
    public required IReadOnlyList<ClipHeaderDto> Clips { get; init; }
}

public sealed record ClipFetchBody
{
    [JsonPropertyName("event_ids")]
    public required IReadOnlyList<string> EventIds { get; init; }
}

public sealed record ClipPayloadItemDto
{
    [JsonPropertyName("event_id")]
    public required string EventId { get; init; }

    [JsonPropertyName("origin_device_id")]
    public required string OriginDeviceId { get; init; }

    [JsonPropertyName("origin_seq")]
    public required long OriginSeq { get; init; }

    [JsonPropertyName("kind")]
    public required string Kind { get; init; }

    [JsonPropertyName("content")]
    public required string Content { get; init; }

    [JsonPropertyName("content_hash")]
    public required string ContentHash { get; init; }

    [JsonPropertyName("utf8_bytes")]
    public required long Utf8Bytes { get; init; }

    [JsonPropertyName("source_app")]
    public string? SourceApp { get; init; }

    [JsonPropertyName("created_at_ms")]
    public required long CreatedAtMs { get; init; }

    [JsonPropertyName("expires_at_ms")]
    public long? ExpiresAtMs { get; init; }
}

public sealed record ClipPayloadBody
{
    [JsonPropertyName("clips")]
    public required IReadOnlyList<ClipPayloadItemDto> Clips { get; init; }
}

public sealed record ClipPayloadBeginBody
{
    [JsonPropertyName("transfer_id")]
    public required string TransferId { get; init; }

    [JsonPropertyName("event_id")]
    public required string EventId { get; init; }

    [JsonPropertyName("chunk_count")]
    public required long ChunkCount { get; init; }

    [JsonPropertyName("encoded_bytes")]
    public required long EncodedBytes { get; init; }

    [JsonPropertyName("content_hash")]
    public required string ContentHash { get; init; }

    [JsonPropertyName("mime_type")]
    public required string MimeType { get; init; }
}

public sealed record ClipPayloadChunkBody
{
    [JsonPropertyName("transfer_id")]
    public required string TransferId { get; init; }

    [JsonPropertyName("event_id")]
    public required string EventId { get; init; }

    [JsonPropertyName("chunk_index")]
    public required long ChunkIndex { get; init; }

    [JsonPropertyName("chunk_count")]
    public required long ChunkCount { get; init; }

    [JsonPropertyName("chunk_bytes")]
    public required long ChunkBytes { get; init; }

    [JsonPropertyName("data")]
    public required string Data { get; init; }
}

public sealed record ClipPayloadEndBody
{
    [JsonPropertyName("transfer_id")]
    public required string TransferId { get; init; }

    [JsonPropertyName("event_id")]
    public required string EventId { get; init; }

    [JsonPropertyName("content_hash")]
    public required string ContentHash { get; init; }
}

public sealed record AckRangesBody
{
    [JsonPropertyName("acks")]
    public required IReadOnlyList<OriginRangesDto> Acks { get; init; }
}

public sealed record ErrorBody
{
    [JsonPropertyName("code")]
    public required string Code { get; init; }

    [JsonPropertyName("retryable")]
    public required bool Retryable { get; init; }

    [JsonPropertyName("failed_type")]
    public string? FailedType { get; init; }

    [JsonPropertyName("retry_after_ms")]
    public long? RetryAfterMs { get; init; }
}

public sealed record PingBody
{
    [JsonPropertyName("sent_at_ms")]
    public required long SentAtMs { get; init; }
}

public sealed record PongBody
{
    [JsonPropertyName("ping_sent_at_ms")]
    public required long PingSentAtMs { get; init; }

    [JsonPropertyName("sent_at_ms")]
    public required long SentAtMs { get; init; }
}
