using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
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
    long ExportedAtMs,
    string ExportingDeviceId,
    string Platform,
    int EventCount);

/// <summary>One validated clip record of an export file: a live body or a terminal tombstone.</summary>
public sealed record HistoryExportedClip(
    Guid EventId,
    string OriginDeviceId,
    long OriginSeq,
    string? Content,
    string? ContentHash,
    string? SourceApp,
    long CreatedAtMs,
    long? ExpiresAtMs,
    long? DeletedAtMs,
    string? TerminalReason)
{
    public bool IsTerminal => TerminalReason is not null;
}

/// <summary>
/// Line-level reader/writer for docs/export-format-v1.md (JSON Lines: one header record,
/// then one clip record per line). Parsing is strict — unknown fields, missing fields, and
/// wrong types reject the record — and every clip's content hash is recomputed, so a
/// tampered or truncated file fails before the store applies anything.
/// </summary>
public static class HistoryExportFormat
{
    public const int FormatVersion = 1;
    public const string Format = "clipsync-history";
    public const string SuggestedExtension = ".jsonl";

    private static readonly string[] AllowedTerminalReasons =
        ["local_only", "deleted", "expired", "policy_filtered", "not_found"];

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
        return JsonSerializer.Serialize(
            new HeaderDto
            {
                Type = "header",
                Format = Format,
                FormatVersion = FormatVersion,
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
        return JsonSerializer.Serialize(
            new ClipDto
            {
                Type = "clip",
                EventId = clip.EventId.ToString("D"),
                OriginDeviceId = clip.OriginDeviceId,
                OriginSeq = clip.OriginSeq,
                Kind = "text",
                Content = clip.Content,
                ContentHash = clip.ContentHash,
                SourceApp = clip.SourceApp,
                CreatedAtMs = clip.CreatedAtMs,
                ExpiresAtMs = clip.ExpiresAtMs,
                DeletedAtMs = clip.DeletedAtMs,
                TerminalReason = clip.TerminalReason
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

        if (dto.FormatVersion != FormatVersion)
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.UnsupportedVersion,
                $"Export format version {dto.FormatVersion} is not supported (expected {FormatVersion}).");
        }

        if (dto.EventCount < 0 || dto.ExportedAtMs < 0 || string.IsNullOrWhiteSpace(dto.ExportingDeviceId))
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.BadHeader,
                "The header record carries out-of-range values.");
        }

        return new HistoryExportHeader(dto.ExportedAtMs, dto.ExportingDeviceId, dto.Platform, dto.EventCount);
    }

    public static HistoryExportedClip ParseClipLine(string line, int lineNumber)
    {
        var dto = Deserialize<ClipDto>(line, HistoryTransferErrorCodes.MalformedRecord, lineNumber);
        if (dto.Type != "clip" || dto.Kind != "text")
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

        if (dto.TerminalReason is null)
        {
            if (dto.Content is null || dto.ContentHash is null || dto.DeletedAtMs is not null)
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
        else
        {
            if (!AllowedTerminalReasons.Contains(dto.TerminalReason, StringComparer.Ordinal))
            {
                throw Malformed(lineNumber, "terminal_reason is not a known value");
            }

            if (dto.Content is not null || dto.ContentHash is not null || dto.SourceApp is not null
                || dto.DeletedAtMs is null)
            {
                throw Malformed(lineNumber, "a terminal record must carry no content and a deleted_at_ms");
            }
        }

        return new HistoryExportedClip(
            eventId,
            dto.OriginDeviceId,
            dto.OriginSeq,
            dto.Content,
            dto.ContentHash,
            dto.SourceApp,
            dto.CreatedAtMs,
            dto.ExpiresAtMs,
            dto.DeletedAtMs,
            dto.TerminalReason);
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
    }
}
