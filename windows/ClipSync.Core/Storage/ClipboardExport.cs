using System.Text;
using System.Text.Json;
using ClipSync.Core.Media;

namespace ClipSync.Core.Storage;

/// <summary>
/// Encodes clip rows as JSON Lines for a user-triggered local export.
/// The returned string contains plaintext clipboard bodies. Call only after
/// an explicit user action. Never write the result to Console, Debug, or logs.
/// format_version 2 adds a header line and copies image blobs to a sibling media directory.
/// </summary>
public static class ClipboardExport
{
    public const string FormatName = "clipsync.export";
    public const int FormatVersion = 2;

    public static async ValueTask<string> EncodeJsonLinesAsync(
        SqliteClipboardEventStore store,
        string? mediaOutputDirectory = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(store);
        var rows = await store.SearchAsync(new ClipboardHistoryQuery(), cancellationToken).ConfigureAwait(false);
        if (mediaOutputDirectory is not null)
        {
            foreach (var row in rows)
            {
                if (!row.IsImage || string.IsNullOrEmpty(row.ContentHash) || !store.Media.Exists(row.ContentHash))
                {
                    continue;
                }

                Directory.CreateDirectory(mediaOutputDirectory);
                var destination = Path.Combine(mediaOutputDirectory, row.ContentHash);
                if (!File.Exists(destination))
                {
                    File.Copy(store.Media.RequirePath(row.ContentHash), destination);
                }
            }
        }

        return EncodeJsonLines(
            rows,
            includeHeader: rows.Any(row => row.IsImage),
            originDeviceId: store.LocalDeviceId);
    }

    public static string EncodeJsonLines(IReadOnlyList<ClipboardHistoryEntry> rows) =>
        EncodeJsonLines(rows, includeHeader: false, originDeviceId: null);

    public static string EncodeJsonLines(
        IReadOnlyList<ClipboardHistoryEntry> rows,
        bool includeHeader,
        string? originDeviceId)
    {
        ArgumentNullException.ThrowIfNull(rows);
        if (rows.Count == 0 && !includeHeader)
        {
            return string.Empty;
        }

        var builder = new StringBuilder();
        if (includeHeader)
        {
            builder.Append(EncodeHeader(originDeviceId ?? string.Empty, DateTimeOffset.UtcNow));
            builder.Append('\n');
        }

        foreach (var row in rows)
        {
            builder.Append(EncodeRow(row));
            builder.Append('\n');
        }

        return builder.ToString();
    }

    public static int CountExportedRows(string jsonl)
    {
        if (string.IsNullOrEmpty(jsonl))
        {
            return 0;
        }

        var count = 0;
        foreach (var raw in jsonl.Split(['\n', '\r'], StringSplitOptions.RemoveEmptyEntries))
        {
            if (raw.Contains("\"format\":\"clipsync.export\"", StringComparison.Ordinal))
            {
                continue;
            }

            count++;
        }

        return count;
    }

    private static string EncodeHeader(string originDeviceId, DateTimeOffset exportedAt)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("format", FormatName);
            writer.WriteNumber("format_version", FormatVersion);
            writer.WriteNumber("exported_at", exportedAt.ToUnixTimeMilliseconds());
            writer.WriteString("origin_device_id", originDeviceId);
            writer.WriteString("platform", "windows");
            writer.WriteBoolean("contains_plaintext_bodies", true);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private static string EncodeRow(ClipboardHistoryEntry row)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("event_id", row.EventId);
            writer.WriteString("origin_device_id", row.OriginDeviceId);
            writer.WriteNumber("origin_seq", row.OriginSequence);
            writer.WriteString("kind", row.Kind);
            if (row.IsImage)
            {
                writer.WriteNull("content");
            }
            else
            {
                writer.WriteString("content", row.Text);
            }

            writer.WriteString("content_hash", row.ContentHash);
            if (row.SourceProcess is null)
            {
                writer.WriteNull("source_app");
            }
            else
            {
                writer.WriteString("source_app", row.SourceProcess);
            }

            writer.WriteNumber("created_at", row.CreatedAt.ToUnixTimeMilliseconds());
            if (row.ExpiresAt is null)
            {
                writer.WriteNull("expires_at");
            }
            else
            {
                writer.WriteNumber("expires_at", row.ExpiresAt.Value.ToUnixTimeMilliseconds());
            }

            if (row.IsImage)
            {
                writer.WriteString("mime_type", row.MimeType ?? MediaLimits.MimePng);
                writer.WriteNumber("encoded_bytes", row.EncodedBytes ?? 0);
                writer.WriteNumber("pixel_width", row.PixelWidth ?? 0);
                writer.WriteNumber("pixel_height", row.PixelHeight ?? 0);
                writer.WriteString("media_file", row.ContentHash);
            }

            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }
}
