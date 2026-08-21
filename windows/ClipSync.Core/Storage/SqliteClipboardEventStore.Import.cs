using System.Data;
using Microsoft.Data.Sqlite;

namespace ClipSync.Core.Storage;

public sealed partial class SqliteClipboardEventStore
{
    /// <summary>
    /// Inserts a history row if <paramref name="row"/>.EventId is absent.
    /// Local-only: no outbox fan-out, no receive-state or peer-cursor updates,
    /// and no live clipboard write. A unique origin-sequence collision is a skip.
    /// </summary>
    public async ValueTask<bool> TryInsertImportedLocalAsync(
        ImportedClipboardRow row,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(row);
        ArgumentException.ThrowIfNullOrWhiteSpace(row.OriginDeviceId);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken).ConfigureAwait(false);
        try
        {
            int inserted;
            await using (var command = connection.CreateCommand())
            {
                command.Transaction = transaction;
                command.CommandText = """
                    INSERT OR IGNORE INTO clips (
                        event_id,
                        origin_device_id,
                        origin_seq,
                        kind,
                        content,
                        content_hash,
                        source_app,
                        created_at,
                        expires_at,
                        deleted_at,
                        terminal_reason)
                    VALUES (
                        $event_id,
                        $origin,
                        $seq,
                        $kind,
                        $content,
                        $hash,
                        $source_app,
                        $created_at,
                        $expires_at,
                        NULL,
                        'local_only');
                    """;
                command.Parameters.AddWithValue("$event_id", row.EventId.ToString("D"));
                command.Parameters.AddWithValue("$origin", row.OriginDeviceId);
                command.Parameters.AddWithValue("$seq", row.OriginSequence);
                command.Parameters.AddWithValue("$kind", row.Kind);
                command.Parameters.AddWithValue(
                    "$content",
                    string.Equals(row.Kind, "image", StringComparison.Ordinal)
                        ? DBNull.Value
                        : (object?)row.Content ?? string.Empty);
                command.Parameters.AddWithValue("$hash", row.ContentHash);
                command.Parameters.AddWithValue("$source_app", (object?)row.SourceApp ?? DBNull.Value);
                command.Parameters.AddWithValue("$created_at", row.CreatedAt.ToUnixTimeMilliseconds());
                command.Parameters.AddWithValue(
                    "$expires_at",
                    (object?)row.ExpiresAt?.ToUnixTimeMilliseconds() ?? DBNull.Value);
                inserted = await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            if (inserted == 1 && string.Equals(row.Kind, "image", StringComparison.Ordinal))
            {
                if (row.MimeType is null || row.EncodedBytes is null
                    || row.PixelWidth is null || row.PixelHeight is null)
                {
                    throw new InvalidDataException("Imported image row is missing media metadata.");
                }

                await using var blob = connection.CreateCommand();
                blob.Transaction = transaction;
                blob.CommandText = """
                    INSERT INTO media_blobs (
                        content_hash, mime_type, encoded_bytes, pixel_width, pixel_height, state, created_at)
                    VALUES ($hash, $mime, $bytes, $width, $height, 'ready', $created_at)
                    ON CONFLICT(content_hash) DO UPDATE SET state = 'ready';
                    """;
                blob.Parameters.AddWithValue("$hash", row.ContentHash);
                blob.Parameters.AddWithValue("$mime", row.MimeType);
                blob.Parameters.AddWithValue("$bytes", row.EncodedBytes.Value);
                blob.Parameters.AddWithValue("$width", row.PixelWidth.Value);
                blob.Parameters.AddWithValue("$height", row.PixelHeight.Value);
                blob.Parameters.AddWithValue("$created_at", row.CreatedAt.ToUnixTimeMilliseconds());
                await blob.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);

                await using var mediaRef = connection.CreateCommand();
                mediaRef.Transaction = transaction;
                mediaRef.CommandText = """
                    INSERT INTO clip_media (event_id, content_hash, state)
                    VALUES ($event_id, $hash, 'ready');
                    """;
                mediaRef.Parameters.AddWithValue("$event_id", row.EventId.ToString("D"));
                mediaRef.Parameters.AddWithValue("$hash", row.ContentHash);
                await mediaRef.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            if (inserted == 1
                && string.Equals(row.OriginDeviceId, localDeviceId, StringComparison.Ordinal))
            {
                await using var sequence = connection.CreateCommand();
                sequence.Transaction = transaction;
                sequence.CommandText = """
                    INSERT INTO local_sequences (device_id, next_seq)
                    VALUES ($device_id, $next)
                    ON CONFLICT(device_id) DO UPDATE SET
                        next_seq = MAX(local_sequences.next_seq, excluded.next_seq);
                    """;
                sequence.Parameters.AddWithValue("$device_id", localDeviceId);
                sequence.Parameters.AddWithValue("$next", row.OriginSequence + 1);
                await sequence.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            }

            await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
            return inserted == 1;
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }
}
