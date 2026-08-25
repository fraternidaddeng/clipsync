using System.Data;
using ClipSync.Core.Media;
using Microsoft.Data.Sqlite;

namespace ClipSync.Core.Storage;

public sealed partial class SqliteClipboardEventStore
{
    /// <summary>
    /// Streams the whole clips table — text and image, live rows and terminal tombstones —
    /// as an export-format JSON Lines document (docs/export-format-v1.md / v2). The header
    /// declares format_version 1 when nothing needs v2 (so older builds keep importing) and
    /// 2 when image rows or unsupported_media tombstones exist. Live image records embed the
    /// encoded blob bytes as base64 when they are on disk and within the 16 MiB cap; a
    /// missing blob degrades that record to metadata-only instead of failing the export.
    /// Read-only; returns the exported event count.
    /// </summary>
    public async ValueTask<int> ExportHistoryAsync(
        TextWriter writer,
        DateTimeOffset exportedAt,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(writer);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var eventCount = checked((int)await ReadScalarInt64Async(
            connection,
            null,
            "SELECT COUNT(*) FROM clips;",
            cancellationToken).ConfigureAwait(false));
        var needsV2 = await ReadScalarInt64Async(
            connection,
            null,
            "SELECT EXISTS(SELECT 1 FROM clips WHERE kind = 'image' OR terminal_reason = 'unsupported_media');",
            cancellationToken).ConfigureAwait(false) == 1;

        await WriteLineAsync(
            writer,
            HistoryExportFormat.WriteHeaderLine(new HistoryExportHeader(
                needsV2 ? HistoryExportFormat.FormatVersion : HistoryExportFormat.TextOnlyFormatVersion,
                exportedAt.ToUnixTimeMilliseconds(),
                localDeviceId,
                "windows",
                eventCount)),
            cancellationToken).ConfigureAwait(false);

        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT c.event_id, c.origin_device_id, c.origin_seq, c.kind, c.content, c.content_hash,
                   c.source_app, c.created_at, c.expires_at, c.deleted_at, c.terminal_reason,
                   b.mime_type, b.encoded_bytes, b.pixel_width, b.pixel_height
            FROM clips c
            LEFT JOIN clip_media m ON m.event_id = c.event_id
            LEFT JOIN media_blobs b ON b.content_hash = m.content_hash
            ORDER BY c.origin_device_id, c.origin_seq;
            """;
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            var kind = reader.GetString(3);
            var terminalReason = reader.IsDBNull(10) ? null : reader.GetString(10);
            var contentHash = reader.GetString(5);
            HistoryExportedMedia? exportedMedia = null;
            if (kind == MediaLimits.KindImage && terminalReason is null)
            {
                if (reader.IsDBNull(11))
                {
                    // Live image rows always reference their blob metadata; assert rather
                    // than silently exporting a shape a re-import would misread.
                    throw new InvalidOperationException("A live image clip has no media metadata row.");
                }

                exportedMedia = new HistoryExportedMedia(
                    reader.GetString(11),
                    reader.GetInt32(12),
                    reader.GetInt32(13),
                    reader.GetInt32(14),
                    TryReadBlobForEmbedding(contentHash));
            }

            var clip = new HistoryExportedClip(
                Guid.Parse(reader.GetString(0)),
                reader.GetString(1),
                reader.GetInt64(2),
                kind,
                terminalReason is null && kind == MediaLimits.KindText ? reader.GetString(4) : null,
                terminalReason is null ? contentHash : null,
                terminalReason is null && !reader.IsDBNull(6) ? reader.GetString(6) : null,
                reader.GetInt64(7),
                reader.IsDBNull(8) ? null : reader.GetInt64(8),
                reader.IsDBNull(9) ? null : reader.GetInt64(9),
                terminalReason,
                exportedMedia);
            await WriteLineAsync(writer, HistoryExportFormat.WriteClipLine(clip), cancellationToken)
                .ConfigureAwait(false);
        }

        await writer.FlushAsync(cancellationToken).ConfigureAwait(false);
        return eventCount;
    }

    /// <summary>Encoded blob bytes for embedding, or null when the file is missing/unreadable/over the cap.</summary>
    private byte[]? TryReadBlobForEmbedding(string contentHash)
    {
        try
        {
            if (!media.Exists(contentHash))
            {
                return null;
            }

            var bytes = media.ReadAllBytes(contentHash);
            return bytes.Length <= HistoryExportFormat.MaxEmbeddedMediaBytes ? bytes : null;
        }
        catch (IOException)
        {
            return null;
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>
    /// Merge-imports an export-format v1/v2 document. The whole file is parsed and validated
    /// first (including embedded image bytes: base64, size cap, hash, magic/dimensions), then
    /// applied in one transaction: idempotent on (origin_device_id, origin_seq), no outbox
    /// fan-out, receive vector advanced, and the local sequence allocator bumped past any
    /// restored own-origin events. Embedded image bytes are committed into the content-addressed
    /// blob store; metadata-only image records restore the row with a missing-media link.
    /// A validation failure changes nothing.
    /// </summary>
    public async ValueTask<HistoryImportResult> ImportHistoryAsync(
        TextReader reader,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(reader);
        await EnsureInitializedAsync(cancellationToken).ConfigureAwait(false);

        var (header, clips) = await ParseDocumentAsync(reader, cancellationToken).ConfigureAwait(false);
        if (clips.Count != header.EventCount)
        {
            throw new HistoryTransferException(
                HistoryTransferErrorCodes.CountMismatch,
                $"The header announces {header.EventCount} events but the file carries {clips.Count}.");
        }

        // Blob writes and the row transaction happen under the media lifecycle lock, like
        // every other image ingress, so concurrent GC cannot race a half-imported blob.
        await mediaLifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
            await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(
                IsolationLevel.Serializable,
                cancellationToken).ConfigureAwait(false);
            try
            {
                var imported = 0;
                var skipped = 0;
                var conflicts = 0;
                long maxOwnOriginSeq = 0;
                foreach (var clip in clips)
                {
                    var existing = await CheckRemoteIdentityAsync(
                        connection,
                        transaction,
                        clip.EventId,
                        clip.OriginDeviceId,
                        clip.OriginSeq,
                        clip.ContentHash,
                        cancellationToken).ConfigureAwait(false);
                    switch (existing)
                    {
                        case RemoteStoreResult.AlreadyPersisted:
                            skipped++;
                            continue;
                        case RemoteStoreResult.IdentityConflict:
                            conflicts++;
                            continue;
                    }

                    await InsertImportedClipAsync(connection, transaction, clip, cancellationToken).ConfigureAwait(false);
                    await AdvanceRemoteReceiveStateAsync(
                        connection,
                        transaction,
                        clip.OriginDeviceId,
                        clip.OriginSeq,
                        cancellationToken).ConfigureAwait(false);
                    if (string.Equals(clip.OriginDeviceId, localDeviceId, StringComparison.Ordinal))
                    {
                        maxOwnOriginSeq = Math.Max(maxOwnOriginSeq, clip.OriginSeq);
                    }

                    imported++;
                }

                if (maxOwnOriginSeq > 0)
                {
                    await BumpLocalSequenceAsync(connection, transaction, maxOwnOriginSeq + 1, cancellationToken)
                        .ConfigureAwait(false);
                }

                await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
                return new HistoryImportResult(imported, skipped, conflicts);
            }
            catch
            {
                // Blobs committed for rows this transaction did not keep are unreferenced
                // files with fresh timestamps; the ordinary blob GC reclaims them after its
                // grace period.
                await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
                throw;
            }
        }
        finally
        {
            mediaLifecycle.Release();
        }
    }

    private static async ValueTask<(HistoryExportHeader Header, List<HistoryExportedClip> Clips)> ParseDocumentAsync(
        TextReader reader,
        CancellationToken cancellationToken)
    {
        string? headerLine;
        do
        {
            headerLine = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
            if (headerLine is null)
            {
                throw new HistoryTransferException(
                    HistoryTransferErrorCodes.BadHeader,
                    "The file is empty.");
            }
        }
        while (headerLine.Trim().Length == 0);

        var header = HistoryExportFormat.ParseHeaderLine(headerLine);
        var clips = new List<HistoryExportedClip>();
        var lineNumber = 1;
        while (await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false) is { } line)
        {
            lineNumber++;
            if (line.Trim().Length == 0)
            {
                continue;
            }

            clips.Add(HistoryExportFormat.ParseClipLine(line, lineNumber, header.FormatVersion));
        }

        return (header, clips);
    }

    private async ValueTask InsertImportedClipAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        HistoryExportedClip clip,
        CancellationToken cancellationToken)
    {
        await using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO clips (
                    event_id, origin_device_id, origin_seq, kind, content, content_hash,
                    source_app, created_at, expires_at, deleted_at, terminal_reason)
                VALUES ($event_id, $origin, $seq, $kind, $content, $hash, $source_app,
                        $created_at, $expires_at, $deleted_at, $reason);
                """;
            insert.Parameters.AddWithValue("$event_id", clip.EventId.ToString("D"));
            insert.Parameters.AddWithValue("$origin", clip.OriginDeviceId);
            insert.Parameters.AddWithValue("$seq", clip.OriginSeq);
            insert.Parameters.AddWithValue("$kind", clip.Kind);
            // The schema requires image rows to keep content NULL and text rows non-NULL.
            insert.Parameters.AddWithValue(
                "$content",
                clip.IsImage ? DBNull.Value : (object?)(clip.Content ?? string.Empty));
            insert.Parameters.AddWithValue("$hash", clip.ContentHash ?? string.Empty);
            insert.Parameters.AddWithValue("$source_app", (object?)clip.SourceApp ?? DBNull.Value);
            insert.Parameters.AddWithValue("$created_at", clip.CreatedAtMs);
            insert.Parameters.AddWithValue("$expires_at", (object?)clip.ExpiresAtMs ?? DBNull.Value);
            insert.Parameters.AddWithValue("$deleted_at", (object?)clip.DeletedAtMs ?? DBNull.Value);
            insert.Parameters.AddWithValue("$reason", (object?)clip.TerminalReason ?? DBNull.Value);
            await insert.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }

        // Media rows come second: clip_media's foreign key references the clips row above.
        if (clip.IsImage && !clip.IsTerminal && clip.Media is { } importedMedia)
        {
            if (importedMedia.EncodedData is not null)
            {
                // Content-addressed and idempotent; re-validates magic, dimensions,
                // size, and hash exactly like the sync ingress.
                media.CommitBytes(importedMedia.EncodedData, clip.ContentHash);
            }

            await InsertImportedMediaRowsAsync(connection, transaction, clip, importedMedia, cancellationToken)
                .ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Writes the blob metadata and the event-to-blob link for one imported live image.
    /// A metadata row never downgrades from ready; the link is ready exactly when the
    /// bytes are on disk (embedded in the file, or already present by content address).
    /// </summary>
    private async ValueTask InsertImportedMediaRowsAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        HistoryExportedClip clip,
        HistoryExportedMedia importedMedia,
        CancellationToken cancellationToken)
    {
        var bytesOnDisk = media.Exists(clip.ContentHash!);
        await using (var blob = connection.CreateCommand())
        {
            blob.Transaction = transaction;
            blob.CommandText = """
                INSERT INTO media_blobs (
                    content_hash, mime_type, encoded_bytes, pixel_width, pixel_height, state, created_at)
                VALUES ($hash, $mime, $bytes, $width, $height, $state, $created_at)
                ON CONFLICT(content_hash) DO UPDATE
                    SET state = CASE WHEN excluded.state = 'ready' THEN 'ready' ELSE media_blobs.state END;
                """;
            blob.Parameters.AddWithValue("$hash", clip.ContentHash);
            blob.Parameters.AddWithValue("$mime", importedMedia.MimeType);
            blob.Parameters.AddWithValue("$bytes", importedMedia.EncodedBytes);
            blob.Parameters.AddWithValue("$width", importedMedia.PixelWidth);
            blob.Parameters.AddWithValue("$height", importedMedia.PixelHeight);
            blob.Parameters.AddWithValue(
                "$state",
                bytesOnDisk ? MediaLimits.BlobStateReady : MediaLimits.BlobStatePending);
            blob.Parameters.AddWithValue("$created_at", clip.CreatedAtMs);
            await blob.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }

        await using var mediaRef = connection.CreateCommand();
        mediaRef.Transaction = transaction;
        mediaRef.CommandText = """
            INSERT INTO clip_media (event_id, content_hash, state)
            VALUES ($event_id, $hash, $state);
            """;
        mediaRef.Parameters.AddWithValue("$event_id", clip.EventId.ToString("D"));
        mediaRef.Parameters.AddWithValue("$hash", clip.ContentHash);
        mediaRef.Parameters.AddWithValue(
            "$state",
            bytesOnDisk ? MediaLimits.ClipMediaReady : MediaLimits.ClipMediaMissing);
        await mediaRef.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Never lowers the allocator: restored own-origin events must not collide with future captures.</summary>
    private async ValueTask BumpLocalSequenceAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        long nextSeq,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO local_sequences (device_id, next_seq)
            VALUES ($device_id, $next_seq)
            ON CONFLICT(device_id) DO UPDATE SET next_seq = MAX(next_seq, excluded.next_seq);
            """;
        command.Parameters.AddWithValue("$device_id", localDeviceId);
        command.Parameters.AddWithValue("$next_seq", nextSeq);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Writes with a bare LF so the file is byte-identical across platforms.</summary>
    private static async ValueTask WriteLineAsync(TextWriter writer, string line, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        await writer.WriteAsync(line).ConfigureAwait(false);
        await writer.WriteAsync('\n').ConfigureAwait(false);
    }
}
