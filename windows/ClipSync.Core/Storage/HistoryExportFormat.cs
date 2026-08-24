using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;

namespace ClipSync.Core.Storage;

/// <summary>
/// A whole-file import/export failure with a stable error code and no clip content
/// in the message, so callers can surface it and log it safely.
/// </summary>
public sealed class HistoryTransferException(string errorCode, string message) : Exception(message)
{
    public string ErrorCode { get; } = errorCode;
}

public static class HistoryTransferErrorCodes
{
    public const string BadHeader = "BAD_HEADER";
    public const string UnsupportedVersion = "UNSUPPORTED_VERSION";
    public const string MalformedRecord = "MALFORMED_RECORD";
    public const string HashMismatch = "HASH_MISMATCH";
    public const string CountMismatch = "COUNT_MISMATCH";
    public const string ContentTooLarge = "CONTENT_TOO_LARGE";
}

/// <summary>Outcome counts of a merge import; conflicts leave the existing rows untouched.</summary>
public sealed record HistoryImportResult(int Imported, int Skipped, int Conflicts)
{
    public int Total => Imported + Skipped + Conflicts;
}

public sealed record HistoryExportHeader(
    int FormatVersion,
    long ExportedAtMs,
    string ExportingDeviceId,
    string Platform,
    int EventCount);

/// <summary>
/// Blob metadata of one live image record (docs/export-format-v2.md §4.2).
/// <see cref="EncodedData"/> carries the decoded (or to-be-embedded) image bytes;
/// null on a metadata-only record.
/// </summary>
public sealed record HistoryExportedMedia(
    string MimeType,
    int EncodedBytes,
    int PixelWidth,
    int PixelHeight,
    byte[]? EncodedData);

/// <summary>One validated clip record of an export file: a live body or a terminal tombstone.</summary>
public sealed record HistoryExportedClip(
    Guid EventId,
    string OriginDeviceId,
    long OriginSeq,
    string Kind,
    string? Content,
    string? ContentHash,
    string? SourceApp,
    long CreatedAtMs,
    long? ExpiresAtMs,
    long? DeletedAtMs,
    string? TerminalReason,
    HistoryExportedMedia? Media = null)
{
    public bool IsTerminal => TerminalReason is not null;

    public bool IsImage => Kind == MediaLimits.KindImage;
}

/// <summary>
/// Line-level reader/writer for docs/export-format-v1.md and docs/export-format-v2.md
/// (JSON Lines: one header record, then one clip record per line). Parsing is strict —
/// unknown fields, missing fields, and wrong types reject the record — and every clip's
/// content hash (text bytes or embedded image bytes) is recomputed, so a tampered or
/// truncated file fails before the store applies anything.
/// </summary>
public static class HistoryExportFormat
{
    /// <summary>The newest version this writer can produce and this reader accepts.</summary>
    public const int FormatVersion = 2;

    /// <summary>The frozen text-only version, still written when nothing requires v2.</summary>
    public const int TextOnlyFormatVersion = 1;

    public const string Format = "clipsync-history";
    public const string SuggestedExtension = ".jsonl";

    /// <summary>Embedded image bytes cap: the same 16 MiB the protocol and storage enforce.</summary>
    public const int MaxEmbeddedMediaBytes = MediaLimits.MaxEncodedBytes;

    private static readonly string[] TerminalReasonsV1 =
        ["local_only", "deleted", "expired", "policy_filtered", "not_found"];

    private static readonly string[] TerminalReasonsV2 =
        ["local_only", "deleted", "expired", "policy_filtered", "not_found", "unsupported_media"];

    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        NumberHandling = JsonNumberHandling.Strict,
        MaxDepth = 16
    };

    public static string WriteHeaderLine(HistoryExportHeader header)
    {
        ArgumentNullException.ThrowIfNull(header);
        if (header.FormatVersion is not (TextOnlyFormatVersion or FormatVersion))
        {
            throw new ArgumentOutOfRangeException(nameof(header), "Unknown export format version.");
        }

        return JsonSerializer.Serialize(
            new HeaderDto
            {
                Type = "header",
                Format = Format,
                FormatVersion = header.FormatVersion,
                ExportedAtMs = header.ExportedAtMs,
                ExportingDeviceId = header.ExportingDeviceId,
                Platform = header.Platform,
                EventCount = header.EventCount
            },
            Options);
    }

    public static string WriteClipLine(HistoryExportedClip clip)
    {
        ArgumentNullException.ThrowIfNull(clip);
        if (clip.Kind is not (MediaLimits.KindText or MediaLimits.KindImage))
        {
            throw new ArgumentOutOfRangeException(nameof(clip), "Unknown clip kind.");
        }

        if (clip.Media is { EncodedData.Length: > MaxEmbeddedMediaBytes })
        {
            throw new ArgumentOutOfRangeException(nameof(clip), "Embedded image bytes exceed the 16 MiB cap.");
        }

        return JsonSerializer.Serialize(
            new ClipDto
            {
                Type = "clip",
                EventId = clip.EventId.ToString("D"),
                OriginDeviceId = clip.OriginDeviceId,
                OriginSeq = clip.OriginSeq,
                Kind = clip.Kind,
                Content = clip.Content,
                ContentHash = clip.ContentHash,
                SourceApp = clip.SourceApp,
                CreatedAtMs = clip.CreatedAtMs,
                ExpiresAtMs = clip.ExpiresAtMs,
                DeletedAtMs = clip.DeletedAtMs,
                TerminalReason = clip.TerminalReason,
                Media = clip.Media is null
                    ? null
                    : new MediaDto
                    {
                        MimeType = clip.Media.MimeType,
                        EncodedBytes = clip.Media.EncodedBytes,
                        PixelWidth = clip.Media.PixelWidth,
                        PixelHeight = clip.Media.PixelHeight,
                        DataBase64 = clip.Media.EncodedData is null
                            ? null
                            : Convert.ToBase64String(clip.Media.EncodedData)
                    }
            },
            Options);
    }

    public static HistoryExportHeader ParseHeaderLine(string line)
    {
        var dto = Deserialize<HeaderDto>(line, HistoryTransferErrorCodes.BadHeader);
        if (dto.Type != "header" || dto.Format != Format)
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.BadHeader,
                "The file does not start with a clipsync-history header record.");
        }

        if (dto.FormatVersion is not (TextOnlyFormatVersion or FormatVersion))
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.UnsupportedVersion,
                $"Export format version {dto.FormatVersion} is not supported (expected {TextOnlyFormatVersion} or {FormatVersion}).");
        }

        if (dto.EventCount < 0 || dto.ExportedAtMs < 0 || string.IsNullOrWhiteSpace(dto.ExportingDeviceId))
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.BadHeader,
                "The header record carries out-of-range values.");
        }

        return new HistoryExportHeader(
            dto.FormatVersion,
            dto.ExportedAtMs,
            dto.ExportingDeviceId,
            dto.Platform,
            dto.EventCount);
    }

    public static HistoryExportedClip ParseClipLine(string line, int lineNumber, int formatVersion = TextOnlyFormatVersion)
    {
        if (formatVersion is not (TextOnlyFormatVersion or FormatVersion))
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.UnsupportedVersion,
                $"Export format version {formatVersion} is not supported.");
        }

        var dto = Deserialize<ClipDto>(line, HistoryTransferErrorCodes.MalformedRecord, lineNumber);
        var kindAllowed = dto.Kind == MediaLimits.KindText
            || (formatVersion >= FormatVersion && dto.Kind == MediaLimits.KindImage);
        if (dto.Type != "clip" || !kindAllowed)
        {
            throw Malformed(lineNumber, "record type or kind is not supported");
        }

        if (!Guid.TryParseExact(dto.EventId, "D", out var eventId))
        {
            throw Malformed(lineNumber, "event_id is not a canonical UUID");
        }

        if (string.IsNullOrWhiteSpace(dto.OriginDeviceId) || dto.OriginDeviceId.Length > 128)
        {
            throw Malformed(lineNumber, "origin_device_id is missing or too long");
        }

        if (dto.OriginSeq < 1)
        {
            throw Malformed(lineNumber, "origin_seq must be at least 1");
        }

        var allowedReasons = formatVersion >= FormatVersion ? TerminalReasonsV2 : TerminalReasonsV1;
        HistoryExportedMedia? media = null;
        if (dto.TerminalReason is null)
        {
            if (dto.Kind == MediaLimits.KindImage)
            {
                media = ValidateLiveImage(dto, lineNumber);
            }
            else
            {
                ValidateLiveText(dto, lineNumber);
            }
        }
        else
        {
            if (!allowedReasons.Contains(dto.TerminalReason, StringComparer.Ordinal))
            {
                throw Malformed(lineNumber, "terminal_reason is not a known value");
            }

            if (dto.Content is not null || dto.ContentHash is not null || dto.SourceApp is not null
                || dto.DeletedAtMs is null || dto.Media is not null)
            {
                throw Malformed(lineNumber, "a terminal record must carry no content and a deleted_at_ms");
            }
        }

        return new HistoryExportedClip(
            eventId,
            dto.OriginDeviceId,
            dto.OriginSeq,
            dto.Kind,
            dto.Content,
            dto.ContentHash,
            dto.SourceApp,
            dto.CreatedAtMs,
            dto.ExpiresAtMs,
            dto.DeletedAtMs,
            dto.TerminalReason,
            media);
    }

    private static void ValidateLiveText(ClipDto dto, int lineNumber)
    {
        if (dto.Content is null || dto.ContentHash is null || dto.DeletedAtMs is not null || dto.Media is not null)
        {
            throw Malformed(lineNumber, "a live record needs content and hash and no deleted_at_ms");
        }

        if (Encoding.UTF8.GetByteCount(dto.Content) > ProtocolLimits.MaxContentUtf8Bytes)
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.ContentTooLarge,
                $"Line {lineNumber}: content exceeds {ProtocolLimits.MaxContentUtf8Bytes} UTF-8 bytes.");
        }

        var recomputed = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(dto.Content)))
            .ToLowerInvariant();
        if (!string.Equals(recomputed, dto.ContentHash, StringComparison.Ordinal))
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.HashMismatch,
                $"Line {lineNumber}: content_hash does not match the content.");
        }
    }

    private static HistoryExportedMedia ValidateLiveImage(ClipDto dto, int lineNumber)
    {
        if (dto.Content is not null || dto.DeletedAtMs is not null || dto.Media is null)
        {
            throw Malformed(lineNumber, "a live image record needs media and no content or deleted_at_ms");
        }

        if (dto.ContentHash is null || !IsLowercaseSha256Hex(dto.ContentHash))
        {
            throw Malformed(lineNumber, "an image content_hash must be 64 lowercase hex characters");
        }

        var declared = dto.Media;
        if (!MediaLimits.IsSupportedMime(declared.MimeType))
        {
            throw Malformed(lineNumber, "media mime_type is not supported");
        }

        if (declared.EncodedBytes < 1 || declared.EncodedBytes > MaxEmbeddedMediaBytes)
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.ContentTooLarge,
                $"Line {lineNumber}: media encoded_bytes is out of the 1..{MaxEmbeddedMediaBytes} range.");
        }

        if (!MediaLimits.FitsPixelBudget(declared.PixelWidth, declared.PixelHeight))
        {
            throw Malformed(lineNumber, "media dimensions exceed the pixel budget");
        }

        byte[]? decoded = null;
        if (declared.DataBase64 is not null)
        {
            try
            {
                decoded = Convert.FromBase64String(declared.DataBase64);
            }
            catch (FormatException)
            {
                throw Malformed(lineNumber, "media data_base64 is not valid base64");
            }

            if (decoded.Length > MaxEmbeddedMediaBytes)
            {
                throw new HistoryTransferException(
                    HistoryTransferErrorCodes.ContentTooLarge,
                    $"Line {lineNumber}: embedded image bytes exceed {MaxEmbeddedMediaBytes} bytes.");
            }

            if (decoded.Length != declared.EncodedBytes)
            {
                throw Malformed(lineNumber, "embedded image length does not match encoded_bytes");
            }

            var recomputed = Convert.ToHexString(SHA256.HashData(decoded)).ToLowerInvariant();
            if (!string.Equals(recomputed, dto.ContentHash, StringComparison.Ordinal))
            {
                throw new HistoryTransferException(
                    HistoryTransferErrorCodes.HashMismatch,
                    $"Line {lineNumber}: content_hash does not match the embedded image bytes.");
            }

            var inspect = ImageCodec.TryInspect(decoded, out var image);
            if (inspect != ImageCodecError.Ok || image is null
                || image.MimeType != declared.MimeType
                || image.PixelWidth != declared.PixelWidth
                || image.PixelHeight != declared.PixelHeight)
            {
                throw Malformed(lineNumber, "embedded image bytes do not match the declared media metadata");
            }
        }

        return new HistoryExportedMedia(
            declared.MimeType,
            declared.EncodedBytes,
            declared.PixelWidth,
            declared.PixelHeight,
            decoded);
    }

    private static bool IsLowercaseSha256Hex(string value)
    {
        if (value.Length != 64)
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

    private static T Deserialize<T>(string line, string errorCode, int lineNumber = 1)
    {
        try
        {
            return JsonSerializer.Deserialize<T>(line, Options)
                ?? throw new HistoryTransferException(errorCode, $"Line {lineNumber}: record is null.");
        }
        catch (JsonException)
        {
            throw new HistoryTransferException(errorCode, $"Line {lineNumber}: record is not valid JSON for this format.");
        }
    }

    private static HistoryTransferException Malformed(int lineNumber, string detail) =>
        new(HistoryTransferErrorCodes.MalformedRecord, $"Line {lineNumber}: {detail}.");

    private sealed class HeaderDto
    {
        [JsonPropertyName("type")]
        public required string Type { get; init; }

        [JsonPropertyName("format")]
        public required string Format { get; init; }

        [JsonPropertyName("format_version")]
        public required int FormatVersion { get; init; }

        [JsonPropertyName("exported_at_ms")]
        public required long ExportedAtMs { get; init; }

        [JsonPropertyName("exporting_device_id")]
        public required string ExportingDeviceId { get; init; }

        [JsonPropertyName("platform")]
        public required string Platform { get; init; }

        [JsonPropertyName("event_count")]
        public required int EventCount { get; init; }
    }

    private sealed class ClipDto
    {
        [JsonPropertyName("type")]
        public required string Type { get; init; }

        [JsonPropertyName("event_id")]
        public required string EventId { get; init; }

        [JsonPropertyName("origin_device_id")]
        public required string OriginDeviceId { get; init; }

        [JsonPropertyName("origin_seq")]
        public required long OriginSeq { get; init; }

        [JsonPropertyName("kind")]
        public required string Kind { get; init; }

        [JsonPropertyName("content")]
        public required string? Content { get; init; }

        [JsonPropertyName("content_hash")]
        public required string? ContentHash { get; init; }

        [JsonPropertyName("source_app")]
        public required string? SourceApp { get; init; }

        [JsonPropertyName("created_at_ms")]
        public required long CreatedAtMs { get; init; }

        [JsonPropertyName("expires_at_ms")]
        public required long? ExpiresAtMs { get; init; }

        [JsonPropertyName("deleted_at_ms")]
        public required long? DeletedAtMs { get; init; }

        [JsonPropertyName("terminal_reason")]
        public required string? TerminalReason { get; init; }

        // Optional so v1 lines (which never carry it) still parse; omitted when null so
        // v1 output stays byte-compatible with pre-v2 writers.
        [JsonPropertyName("media")]
        [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
        public MediaDto? Media { get; init; }
    }

    private sealed class MediaDto
    {
        [JsonPropertyName("mime_type")]
        public required string MimeType { get; init; }

        [JsonPropertyName("encoded_bytes")]
        public required int EncodedBytes { get; init; }

        [JsonPropertyName("pixel_width")]
        public required int PixelWidth { get; init; }

        [JsonPropertyName("pixel_height")]
        public required int PixelHeight { get; init; }

        [JsonPropertyName("data_base64")]
        [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
        public string? DataBase64 { get; init; }
    }
}
