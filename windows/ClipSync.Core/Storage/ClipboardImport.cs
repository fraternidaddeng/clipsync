using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;
using Microsoft.Data.Sqlite;

namespace ClipSync.Core.Storage;

/// <summary>
/// Parses and applies the JSON Lines format <see cref="ClipboardExport"/> writes.
/// Malformed or oversized lines are counted and skipped; the import never aborts
/// on a bad line. Never log the input, paths, or clipboard bodies.
/// </summary>
public static class ClipboardImport
{
    /// <summary>
    /// Hard cap for a whole import file. Exports are bounded by history size;
    /// anything past this is a hostile or wrong file, not a backup.
    /// </summary>
    public const long MaximumImportBytes = 256L * 1024 * 1024;

    /// <summary>One JSONL line: 1 MiB body plus generous metadata headroom.</summary>
    public const int MaximumLineChars = 8 * 1024 * 1024;

    /// <summary>Matches the protocol/pairing parser depth cap.</summary>
    private static readonly JsonDocumentOptions DocumentOptions = new() { MaxDepth = 16 };

    public static ClipboardImportParseResult ParseJsonLines(string jsonl)
    {
        ArgumentNullException.ThrowIfNull(jsonl);
        if (jsonl.Length == 0)
        {
            return new ClipboardImportParseResult([], 0);
        }

        var rows = new List<ImportedClipboardRow>();
        var skipped = 0;
        foreach (var raw in jsonl.Split(['\n', '\r'], StringSplitOptions.RemoveEmptyEntries))
        {
            var line = raw.Trim();
            if (line.Length == 0)
            {
                continue;
            }

            if (line.Length > MaximumLineChars)
            {
                skipped++;
                continue;
            }

            switch (ClassifyLine(line, out var row))
            {
                case LineKind.Ignore:
                    break;
                case LineKind.Skip:
                    skipped++;
                    break;
                case LineKind.Row:
                    rows.Add(row!);
                    break;
            }
        }

        return new ClipboardImportParseResult(rows, skipped);
    }

    public static async ValueTask<ClipboardImportResult> ImportJsonLinesAsync(
        SqliteClipboardEventStore store,
        string jsonl,
        string? mediaDirectory = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(store);
        var parsed = ParseJsonLines(jsonl);
        var imported = 0;
        var skipped = parsed.Skipped;
        foreach (var row in parsed.Rows)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                if (string.Equals(row.Kind, "image", StringComparison.Ordinal)
                    && !TryCommitImportedImage(store, row, mediaDirectory))
                {
                    skipped++;
                    continue;
                }

                if (await store.TryInsertImportedLocalAsync(row, cancellationToken).ConfigureAwait(false))
                {
                    imported++;
                }
                else
                {
                    skipped++;
                }
            }
            catch (Exception exception) when (exception is SqliteException or InvalidDataException or IOException)
            {
                skipped++;
            }
        }

        return new ClipboardImportResult(imported, skipped);
    }

    private static bool TryCommitImportedImage(
        SqliteClipboardEventStore store,
        ImportedClipboardRow row,
        string? mediaDirectory)
    {
        if (string.IsNullOrWhiteSpace(mediaDirectory) || string.IsNullOrWhiteSpace(row.MediaFileName))
        {
            return false;
        }

        var fileName = Path.GetFileName(row.MediaFileName);
        if (fileName.Length != 64 || fileName != row.ContentHash)
        {
            return false;
        }

        var path = Path.Combine(mediaDirectory, fileName);
        if (!File.Exists(path))
        {
            return false;
        }

        var bytes = File.ReadAllBytes(path);
        var inspect = ImageCodec.TryInspect(bytes, out var image, row.ContentHash);
        return inspect == ImageCodecError.Ok
            && image is not null
            && image.MimeType == row.MimeType
            && image.PixelWidth == row.PixelWidth
            && image.PixelHeight == row.PixelHeight
            && image.EncodedBytes == row.EncodedBytes
            && store.Media.CommitBytes(bytes, row.ContentHash).ContentHash == row.ContentHash;
    }

    private static LineKind ClassifyLine(string line, out ImportedClipboardRow? row)
    {
        row = null;
        try
        {
            using var document = JsonDocument.Parse(line, DocumentOptions);
            if (document.RootElement.ValueKind != JsonValueKind.Object)
            {
                return LineKind.Skip;
            }

            var root = document.RootElement;
            if (root.TryGetProperty("format", out var format)
                && format.ValueKind == JsonValueKind.String
                && string.Equals(format.GetString(), "clipsync.export", StringComparison.Ordinal))
            {
                return LineKind.Ignore;
            }

            if (!TryReadEvent(root, out row, out var oversized) || oversized)
            {
                return LineKind.Skip;
            }

            return LineKind.Row;
        }
        catch (JsonException)
        {
            return LineKind.Skip;
        }
    }

    private static bool TryReadEvent(
        JsonElement root,
        out ImportedClipboardRow? row,
        out bool oversized)
    {
        row = null;
        oversized = false;
        if (!TryReadString(root, "event_id", out var eventIdText)
            || !Guid.TryParse(eventIdText, out var eventId)
            || !TryReadString(root, "origin_device_id", out var originDeviceId)
            || string.IsNullOrWhiteSpace(originDeviceId)
            || !TryReadInt64(root, "origin_seq", out var originSeq)
            || originSeq < 1
            || !TryReadString(root, "kind", out var kind)
            || !TryReadString(root, "content_hash", out var contentHash)
            || !TryReadInt64(root, "created_at", out var createdAtMs)
            || !TryReadOptionalString(root, "source_app", out var sourceApp)
            || !TryReadOptionalInt64(root, "expires_at", out var expiresAtMs))
        {
            return false;
        }

        if (string.Equals(kind, "text", StringComparison.Ordinal))
        {
            if (!TryReadString(root, "content", out var content))
            {
                return false;
            }

            if (Encoding.UTF8.GetByteCount(content) > ClipboardCapturePolicy.MaximumUtf8Bytes)
            {
                oversized = true;
                return true;
            }

            if (content.Length > 0
                && !string.Equals(contentHash, Hash(content), StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }

            row = new ImportedClipboardRow(
                eventId,
                originDeviceId,
                originSeq,
                content,
                contentHash,
                sourceApp,
                DateTimeOffset.FromUnixTimeMilliseconds(createdAtMs),
                expiresAtMs is null ? null : DateTimeOffset.FromUnixTimeMilliseconds(expiresAtMs.Value));
            return true;
        }

        if (!string.Equals(kind, "image", StringComparison.Ordinal))
        {
            return false;
        }

        if (!TryReadOptionalString(root, "content", out var imageContent) || imageContent is not null)
        {
            return false;
        }

        if (!TryReadString(root, "mime_type", out var mime)
            || !MediaLimits.IsSupportedMime(mime)
            || !TryReadInt64(root, "encoded_bytes", out var encodedBytes)
            || encodedBytes is < 1 or > MediaLimits.MaxEncodedBytes
            || !TryReadInt64(root, "pixel_width", out var width)
            || !TryReadInt64(root, "pixel_height", out var height)
            || width > int.MaxValue
            || height > int.MaxValue
            || !MediaLimits.FitsPixelBudget((int)width, (int)height)
            || !TryReadOptionalString(root, "media_file", out var mediaFile)
            || !ProtocolValidationHash(contentHash))
        {
            return false;
        }

        if (mediaFile is not null && (mediaFile != contentHash || mediaFile.Contains('/', StringComparison.Ordinal) || mediaFile.Contains('\\', StringComparison.Ordinal)))
        {
            return false;
        }

        row = new ImportedClipboardRow(
            eventId,
            originDeviceId,
            originSeq,
            null,
            contentHash,
            sourceApp,
            DateTimeOffset.FromUnixTimeMilliseconds(createdAtMs),
            expiresAtMs is null ? null : DateTimeOffset.FromUnixTimeMilliseconds(expiresAtMs.Value),
            "image",
            mime,
            (int)encodedBytes,
            (int)width,
            (int)height,
            mediaFile ?? contentHash);
        return true;
    }

    private static bool ProtocolValidationHash(string value)
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

    private static bool TryReadString(JsonElement root, string name, out string value)
    {
        value = string.Empty;
        if (!root.TryGetProperty(name, out var property)
            || property.ValueKind != JsonValueKind.String
            || property.GetString() is not { } text)
        {
            return false;
        }

        value = text;
        return true;
    }

    private static bool TryReadOptionalString(JsonElement root, string name, out string? value)
    {
        value = null;
        if (!root.TryGetProperty(name, out var property))
        {
            return false;
        }

        switch (property.ValueKind)
        {
            case JsonValueKind.Null:
                return true;
            case JsonValueKind.String:
                value = property.GetString();
                return true;
            default:
                return false;
        }
    }

    private static bool TryReadInt64(JsonElement root, string name, out long value)
    {
        value = 0;
        return root.TryGetProperty(name, out var property)
            && property.ValueKind == JsonValueKind.Number
            && property.TryGetInt64(out value);
    }

    private static bool TryReadOptionalInt64(JsonElement root, string name, out long? value)
    {
        value = null;
        if (!root.TryGetProperty(name, out var property))
        {
            return false;
        }

        if (property.ValueKind == JsonValueKind.Null)
        {
            return true;
        }

        if (property.ValueKind == JsonValueKind.Number && property.TryGetInt64(out var parsed))
        {
            value = parsed;
            return true;
        }

        return false;
    }

    private static string Hash(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private enum LineKind
    {
        Ignore,
        Skip,
        Row
    }
}
