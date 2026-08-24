using System.Data;
using Microsoft.Data.Sqlite;

namespace ClipSync.Core.Storage;

public sealed partial class SqliteClipboardEventStore
{
    /// <summary>
    /// Streams the whole clips table — live rows and terminal tombstones — as an
    /// export-format-v1 JSON Lines document. Read-only; returns the exported event count.
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

        await WriteLineAsync(
            writer,
            HistoryExportFormat.WriteHeaderLine(new HistoryExportHeader(
                exportedAt.ToUnixTimeMilliseconds(),
                localDeviceId,
                "windows",
                eventCount)),
            cancellationToken).ConfigureAwait(false);

        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT event_id, origin_device_id, origin_seq, content, content_hash,
                   source_app, created_at, expires_at, deleted_at, terminal_reason
            FROM clips
            ORDER BY origin_device_id, origin_seq;
            """;
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            var terminalReason = reader.IsDBNull(9) ? null : reader.GetString(9);
            var clip = new HistoryExportedClip(
                Guid.Parse(reader.GetString(0)),
                reader.GetString(1),
                reader.GetInt64(2),
                terminalReason is null ? reader.GetString(3) : null,
                terminalReason is null ? reader.GetString(4) : null,
                terminalReason is null && !reader.IsDBNull(5) ? reader.GetString(5) : null,
                reader.GetInt64(6),
                reader.IsDBNull(7) ? null : reader.GetInt64(7),
                reader.IsDBNull(8) ? null : reader.GetInt64(8),
                terminalReason);
            await WriteLineAsync(writer, HistoryExportFormat.WriteClipLine(clip), cancellationToken)
                .ConfigureAwait(false);
        }

        await writer.FlushAsync(cancellationToken).ConfigureAwait(false);
        return eventCount;
    }

    /// <summary>
    /// Merge-imports an export-format-v1 document. The whole file is parsed and validated
    /// first, then applied in one transaction: idempotent on (origin_device_id, origin_seq),
    /// no outbox fan-out, receive vector advanced, and the local sequence allocator bumped
    /// past any restored own-origin events. A validation failure changes nothing.
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
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
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

            clips.Add(HistoryExportFormat.ParseClipLine(line, lineNumber));
        }

        return (header, clips);
    }

    private static async ValueTask InsertImportedClipAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        HistoryExportedClip clip,
        CancellationToken cancellationToken)
    {
        await using var insert = connection.CreateCommand();
        insert.Transaction = transaction;
        insert.CommandText = """
            INSERT INTO clips (
                event_id, origin_device_id, origin_seq, kind, content, content_hash,
                source_app, created_at, expires_at, deleted_at, terminal_reason)
            VALUES ($event_id, $origin, $seq, 'text', $content, $hash, $source_app,
                    $created_at, $expires_at, $deleted_at, $reason);
            """;
        insert.Parameters.AddWithValue("$event_id", clip.EventId.ToString("D"));
        insert.Parameters.AddWithValue("$origin", clip.OriginDeviceId);
        insert.Parameters.AddWithValue("$seq", clip.OriginSeq);
        insert.Parameters.AddWithValue("$content", clip.Content ?? string.Empty);
        insert.Parameters.AddWithValue("$hash", clip.ContentHash ?? string.Empty);
        insert.Parameters.AddWithValue("$source_app", (object?)clip.SourceApp ?? DBNull.Value);
        insert.Parameters.AddWithValue("$created_at", clip.CreatedAtMs);
        insert.Parameters.AddWithValue("$expires_at", (object?)clip.ExpiresAtMs ?? DBNull.Value);
        insert.Parameters.AddWithValue("$deleted_at", (object?)clip.DeletedAtMs ?? DBNull.Value);
        insert.Parameters.AddWithValue("$reason", (object?)clip.TerminalReason ?? DBNull.Value);
        await insert.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
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
