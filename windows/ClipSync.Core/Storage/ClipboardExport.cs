using System.Text;
using System.Text.Json;

namespace ClipSync.Core.Storage;

/// <summary>
/// Encodes clip rows as JSON Lines for a user-triggered local export.
/// The returned string contains plaintext clipboard bodies. Call only after
/// an explicit user action. Never write the result to Console, Debug, or logs.
/// </summary>
/// <remarks>
/// Field gap vs docs/stage-6-migration-export.md: <see cref="ClipboardHistoryEntry"/>
/// has no kind or terminal_reason. This encoder writes kind as "text" and omits
/// deleted_at / terminal_reason even though DeletedAt is on the read model.
/// <see cref="SqliteClipboardEventStore.SearchAsync"/> also excludes soft-deleted rows.
/// </remarks>
public static class ClipboardExport
{
    public static async ValueTask<string> EncodeJsonLinesAsync(
        SqliteClipboardEventStore store,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(store);
        var rows = await store.SearchAsync(new ClipboardHistoryQuery(), cancellationToken).ConfigureAwait(false);
        return EncodeJsonLines(rows);
    }

    public static string EncodeJsonLines(IReadOnlyList<ClipboardHistoryEntry> rows)
    {
        ArgumentNullException.ThrowIfNull(rows);
        if (rows.Count == 0)
        {
            return string.Empty;
        }

        var builder = new StringBuilder();
        foreach (var row in rows)
        {
            builder.Append(EncodeRow(row));
            builder.Append('\n');
        }

        return builder.ToString();
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
            writer.WriteString("kind", "text");
            writer.WriteString("content", row.Text);
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

            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }
}
