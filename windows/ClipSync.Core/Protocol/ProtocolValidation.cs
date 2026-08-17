using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using ClipSync.Core.Sync;

namespace ClipSync.Core.Protocol;

/// <summary>
/// Mandatory semantic checks from docs/protocol-v1.md that JSON shape validation cannot express.
/// Every check returns a stable reason string for diagnostics; reasons never contain message content.
/// </summary>
public static partial class ProtocolValidation
{
    /// <summary>The only authentication algorithm in protocol v1.</summary>
    public const string HmacSha256 = "hmac-sha256";
    private const string TextKind = "text";
    private static readonly HashSet<string> Platforms = new(StringComparer.Ordinal) { "windows", "android" };

    [GeneratedRegex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")]
    private static partial Regex CanonicalUuidRegex();

    [GeneratedRegex("^[A-Za-z0-9_-]{43}$")]
    private static partial Regex Base64Url256Regex();

    [GeneratedRegex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$")]
    private static partial Regex ClientVersionRegex();

    public static bool IsCanonicalUuid(string? value) =>
        value is not null && CanonicalUuidRegex().IsMatch(value);

    /// <summary>True when the value is unpadded base64url for exactly 32 bytes with zeroed trailing bits.</summary>
    public static bool TryDecodeBase64Url256(string? value, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (value is null || !Base64Url256Regex().IsMatch(value))
        {
            return false;
        }

        var padded = value.Replace('-', '+').Replace('_', '/') + "=";
        Span<byte> decoded = stackalloc byte[33];
        if (!Convert.TryFromBase64String(padded, decoded, out var written) || written != 32)
        {
            return false;
        }

        bytes = decoded[..32].ToArray();
        return string.Equals(EncodeBase64Url(bytes), value, StringComparison.Ordinal);
    }

    public static string EncodeBase64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static string ComputeContentHash(string content) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(content))).ToLowerInvariant();

    public static string? Validate(string messageType, object body) => body switch
    {
        HelloBody hello => ValidateHello(hello),
        ChallengeBody challenge => ValidateChallenge(challenge),
        AuthBody auth => ValidateAuth(auth),
        SyncStateDto vector => ValidateSyncState(vector),
        WantRangesBody want => ValidateOriginRangesList(want.Requests),
        ClipAnnounceBody announce => ValidateAnnounce(announce),
        ClipFetchBody fetch => ValidateFetch(fetch),
        ClipPayloadBody payload => ValidatePayload(payload),
        AckRangesBody acks => ValidateOriginRangesList(acks.Acks),
        ErrorBody error => ValidateError(error),
        PingBody ping => ValidateTimestamp(ping.SentAtMs),
        PongBody pong => ValidateTimestamp(pong.PingSentAtMs) ?? ValidateTimestamp(pong.SentAtMs),
        _ => $"unsupported message type {messageType}"
    };

    private static string? ValidateHello(HelloBody hello)
    {
        if (!IsCanonicalUuid(hello.DeviceId))
        {
            return "hello.device_id is not a canonical UUID";
        }

        if (!Platforms.Contains(hello.Platform))
        {
            return "hello.platform is unknown";
        }

        if (hello.ClientVersion.Length > ProtocolLimits.MaxClientVersionLength
            || !ClientVersionRegex().IsMatch(hello.ClientVersion))
        {
            return "hello.client_version is invalid";
        }

        if (hello.TrustEpoch < 1)
        {
            return "hello.trust_epoch must be at least 1";
        }

        return ValidateSyncState(hello.KnownVector);
    }

    private static string? ValidateChallenge(ChallengeBody challenge)
    {
        if (!string.Equals(challenge.Algorithm, HmacSha256, StringComparison.Ordinal))
        {
            return "challenge.algorithm is unsupported";
        }

        if (!TryDecodeBase64Url256(challenge.Nonce, out _))
        {
            return "challenge.nonce is not 32 bytes of unpadded base64url";
        }

        if (!IsCanonicalUuid(challenge.ChallengerDeviceId) || !IsCanonicalUuid(challenge.ResponderDeviceId))
        {
            return "challenge device ids are not canonical UUIDs";
        }

        if (challenge.TrustEpoch < 1)
        {
            return "challenge.trust_epoch must be at least 1";
        }

        return ValidateTimestamp(challenge.ExpiresAtMs);
    }

    private static string? ValidateAuth(AuthBody auth)
    {
        if (!string.Equals(auth.Algorithm, HmacSha256, StringComparison.Ordinal))
        {
            return "auth.algorithm is unsupported";
        }

        if (!IsCanonicalUuid(auth.ChallengeRequestId))
        {
            return "auth.challenge_request_id is not a canonical UUID";
        }

        if (!IsCanonicalUuid(auth.ResponderDeviceId))
        {
            return "auth.responder_device_id is not a canonical UUID";
        }

        if (auth.TrustEpoch < 1)
        {
            return "auth.trust_epoch must be at least 1";
        }

        return TryDecodeBase64Url256(auth.Proof, out _)
            ? null
            : "auth.proof is not 32 bytes of unpadded base64url";
    }

    private static string? ValidateSyncState(SyncStateDto vector)
    {
        if (vector.Origins.Count > ProtocolLimits.MaxOriginsPerMessage)
        {
            return "known_vector exceeds the origin limit";
        }

        var seenOrigins = new HashSet<string>(StringComparer.Ordinal);
        foreach (var origin in vector.Origins)
        {
            if (!IsCanonicalUuid(origin.OriginDeviceId))
            {
                return "known_vector origin_device_id is not a canonical UUID";
            }

            if (!seenOrigins.Add(origin.OriginDeviceId))
            {
                return "known_vector contains a duplicate origin";
            }

            if (origin.ContiguousSeq < 0)
            {
                return "known_vector contiguous_seq cannot be negative";
            }

            if (origin.ReceivedRanges is not null)
            {
                var rangeError = ValidateRangeList(origin.ReceivedRanges, origin.ContiguousSeq);
                if (rangeError is not null)
                {
                    return rangeError;
                }
            }
        }

        return null;
    }

    private static string? ValidateOriginRangesList(IReadOnlyList<OriginRangesDto> entries)
    {
        if (entries.Count is < 1 or > ProtocolLimits.MaxOriginsPerMessage)
        {
            return "origin range list size is out of bounds";
        }

        var seenOrigins = new HashSet<string>(StringComparer.Ordinal);
        foreach (var entry in entries)
        {
            if (!IsCanonicalUuid(entry.OriginDeviceId))
            {
                return "origin_device_id is not a canonical UUID";
            }

            if (!seenOrigins.Add(entry.OriginDeviceId))
            {
                return "duplicate origin_device_id";
            }

            var rangeError = ValidateRangeList(entry.Ranges, aboveCursor: null);
            if (rangeError is not null)
            {
                return rangeError;
            }
        }

        return null;
    }

    private static string? ValidateRangeList(IReadOnlyList<RangeDto> ranges, long? aboveCursor)
    {
        if (ranges.Count is < 1 or > ProtocolLimits.MaxRangesPerOrigin)
        {
            return "range list size is out of bounds";
        }

        long previousEnd = 0;
        for (var index = 0; index < ranges.Count; index++)
        {
            var range = ranges[index];
            if (range.StartSeq < 1 || range.EndSeq < range.StartSeq)
            {
                return "range bounds are invalid";
            }

            if (index > 0 && range.StartSeq <= previousEnd + 1)
            {
                return "ranges must be sorted, disjoint, and non-adjacent";
            }

            if (aboveCursor is not null && range.StartSeq <= aboveCursor.Value + 1)
            {
                return "received_ranges must start above contiguous_seq + 1";
            }

            previousEnd = range.EndSeq;
        }

        return null;
    }

    private static string? ValidateAnnounce(ClipAnnounceBody announce)
    {
        if (announce.Clips.Count is < 1 or > ProtocolLimits.MaxAnnounceClips)
        {
            return "clip_announce size is out of bounds";
        }

        var seenEventIds = new HashSet<string>(StringComparer.Ordinal);
        var seenSequences = new HashSet<(string Origin, long Seq)>();
        foreach (var clip in announce.Clips)
        {
            var identityError = ValidateClipIdentity(clip.EventId, clip.OriginDeviceId, clip.OriginSeq, seenEventIds, seenSequences);
            if (identityError is not null)
            {
                return identityError;
            }

            switch (clip.Availability)
            {
                case ClipAvailability.Available:
                    if (clip.Reason is not null)
                    {
                        return "available header cannot carry a reason";
                    }

                    if (!string.Equals(clip.Kind, TextKind, StringComparison.Ordinal))
                    {
                        return "available header kind must be text";
                    }

                    if (clip.ContentHash is null || !IsLowercaseSha256(clip.ContentHash))
                    {
                        return "available header content_hash is invalid";
                    }

                    if (clip.Utf8Bytes is null or < 1 or > ProtocolLimits.MaxContentUtf8Bytes)
                    {
                        return "available header utf8_bytes is out of bounds";
                    }

                    if (clip.CreatedAtMs is null || ValidateTimestamp(clip.CreatedAtMs.Value) is not null)
                    {
                        return "available header created_at_ms is invalid";
                    }

                    var metadataError = ValidateOptionalClipMetadata(clip.SourceApp, clip.CreatedAtMs, clip.ExpiresAtMs);
                    if (metadataError is not null)
                    {
                        return metadataError;
                    }

                    break;

                case ClipAvailability.Unavailable:
                    if (clip.Reason is null || !ClipUnavailableReasons.All.Contains(clip.Reason))
                    {
                        return "unavailable header reason is invalid";
                    }

                    if (clip.Kind is not null || clip.ContentHash is not null || clip.Utf8Bytes is not null
                        || clip.SourceApp is not null || clip.CreatedAtMs is not null || clip.ExpiresAtMs is not null)
                    {
                        return "unavailable header cannot carry content metadata";
                    }

                    break;

                default:
                    return "availability is unknown";
            }
        }

        return null;
    }

    private static string? ValidateFetch(ClipFetchBody fetch)
    {
        if (fetch.EventIds.Count is < 1 or > ProtocolLimits.MaxFetchEventIds)
        {
            return "clip_fetch size is out of bounds";
        }

        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var eventId in fetch.EventIds)
        {
            if (!IsCanonicalUuid(eventId))
            {
                return "clip_fetch event_id is not a canonical UUID";
            }

            if (!seen.Add(eventId))
            {
                return "clip_fetch contains a duplicate event_id";
            }
        }

        return null;
    }

    private static string? ValidatePayload(ClipPayloadBody payload)
    {
        if (payload.Clips.Count is < 1 or > ProtocolLimits.MaxPayloadClips)
        {
            return "clip_payload size is out of bounds";
        }

        var seenEventIds = new HashSet<string>(StringComparer.Ordinal);
        var seenSequences = new HashSet<(string Origin, long Seq)>();
        long totalContentBytes = 0;
        foreach (var clip in payload.Clips)
        {
            var identityError = ValidateClipIdentity(clip.EventId, clip.OriginDeviceId, clip.OriginSeq, seenEventIds, seenSequences);
            if (identityError is not null)
            {
                return identityError;
            }

            if (!string.Equals(clip.Kind, TextKind, StringComparison.Ordinal))
            {
                return "clip_payload kind must be text";
            }

            if (clip.Content.Length == 0)
            {
                return "clip_payload content cannot be empty";
            }

            var contentBytes = Encoding.UTF8.GetByteCount(clip.Content);
            if (contentBytes > ProtocolLimits.MaxContentUtf8Bytes)
            {
                return "clip_payload content exceeds 1 MiB";
            }

            if (clip.Utf8Bytes != contentBytes)
            {
                return "clip_payload utf8_bytes does not match the content";
            }

            if (!IsLowercaseSha256(clip.ContentHash)
                || !string.Equals(clip.ContentHash, ComputeContentHash(clip.Content), StringComparison.Ordinal))
            {
                return "clip_payload content_hash does not match the content";
            }

            var timestampError = ValidateTimestamp(clip.CreatedAtMs);
            if (timestampError is not null)
            {
                return timestampError;
            }

            var metadataError = ValidateOptionalClipMetadata(clip.SourceApp, clip.CreatedAtMs, clip.ExpiresAtMs);
            if (metadataError is not null)
            {
                return metadataError;
            }

            totalContentBytes += contentBytes;
            if (totalContentBytes > ProtocolLimits.MaxPayloadBatchContentBytes)
            {
                return "clip_payload batch exceeds 1 MiB of content";
            }
        }

        return null;
    }

    private static string? ValidateClipIdentity(
        string eventId,
        string originDeviceId,
        long originSeq,
        HashSet<string> seenEventIds,
        HashSet<(string Origin, long Seq)> seenSequences)
    {
        if (!IsCanonicalUuid(eventId))
        {
            return "clip event_id is not a canonical UUID";
        }

        if (!IsCanonicalUuid(originDeviceId))
        {
            return "clip origin_device_id is not a canonical UUID";
        }

        if (originSeq < 1)
        {
            return "clip origin_seq must be at least 1";
        }

        if (!seenEventIds.Add(eventId))
        {
            return "duplicate clip event_id";
        }

        if (!seenSequences.Add((originDeviceId, originSeq)))
        {
            return "duplicate clip origin sequence";
        }

        return null;
    }

    private static string? ValidateOptionalClipMetadata(string? sourceApp, long? createdAtMs, long? expiresAtMs)
    {
        if (sourceApp is not null && sourceApp.Length is < 1 or > ProtocolLimits.MaxSourceAppLength)
        {
            return "source_app length is out of bounds";
        }

        if (expiresAtMs is not null)
        {
            var timestampError = ValidateTimestamp(expiresAtMs.Value);
            if (timestampError is not null)
            {
                return timestampError;
            }

            if (createdAtMs is not null && expiresAtMs.Value <= createdAtMs.Value)
            {
                return "expires_at_ms must be greater than created_at_ms";
            }
        }

        return null;
    }

    private static string? ValidateError(ErrorBody error)
    {
        if (!ProtocolErrorCodes.All.Contains(error.Code))
        {
            return "error.code is unknown";
        }

        if (error.FailedType is not null && !ProtocolMessageTypes.All.Contains(error.FailedType))
        {
            return "error.failed_type is unknown";
        }

        if (error.RetryAfterMs is not null && error.RetryAfterMs.Value is < 1 or > ProtocolLimits.MaxRetryAfterMs)
        {
            return "error.retry_after_ms is out of bounds";
        }

        return null;
    }

    private static string? ValidateTimestamp(long unixTimeMs) =>
        unixTimeMs < 0 ? "timestamps cannot be negative" : null;

    /// <summary>True for exactly 64 lowercase hexadecimal characters.</summary>
    public static bool IsLowercaseSha256(string? value)
    {
        if (value is null || value.Length != 64)
        {
            return false;
        }

        foreach (var character in value)
        {
            if (character is not ((>= '0' and <= '9') or (>= 'a' and <= 'f')))
            {
                return false;
            }
        }

        return true;
    }

    public static IReadOnlyList<SequenceRange> ToSequenceRanges(IReadOnlyList<RangeDto> ranges) =>
        ranges.Select(range => new SequenceRange(range.StartSeq, range.EndSeq)).ToArray();

    public static IReadOnlyList<RangeDto> ToRangeDtos(IReadOnlyList<SequenceRange> ranges) =>
        ranges.Select(range => new RangeDto { StartSeq = range.StartSeq, EndSeq = range.EndSeq }).ToArray();
}
