using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Core.Sync;
using Microsoft.Data.Sqlite;
using Xunit.Abstractions;

namespace ClipSync.Tests.Perf;

/// <summary>
/// SQLite history store measurements at 10k / 50k rows. Run with
/// <c>CLIPSYNC_PERF=1 dotnet test --filter FullyQualifiedName~StoragePerfBenchmarks</c>.
/// Rows are bulk-seeded through the schema directly (one transaction) so the seed
/// itself does not dominate; write throughput is measured separately through the store.
/// </summary>
public sealed class StoragePerfBenchmarks(ITestOutputHelper output)
{
    private const string LocalDeviceId = "0a2b7c1e-5d3f-4b8a-9c1d-2e3f4a5b6c7d";
    private const string PeerDeviceId = "1b3c8d2f-6e4a-4c9b-8d2e-3f4a5b6c7d8e";
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

    [PerfFact]
    public Task Queries10k() => RunAsync(10_000);

    [PerfFact]
    public Task Queries50k() => RunAsync(50_000);

    private async Task RunAsync(int rows)
    {
        await using var database = new TemporaryDatabase();
        await using (var seedStore = database.CreateStore())
        {
            await seedStore.InitializeAsync();
            await seedStore.UpsertDeviceAsync(
                new NewPairedDevice(PeerDeviceId, "phone", "android", "fp", "secret"),
                BaseTime);
        }

        var seedElapsed = await SeedAsync(database.Path, rows);
        output.WriteLine(string.Format(CultureInfo.InvariantCulture, "seed {0:N0} rows: {1:F0} ms", rows, seedElapsed.TotalMilliseconds));

        var openSample = await PerfProbe.MeasureAsync(
            $"[{rows}] InitializeAsync (existing db)",
            5,
            async () =>
            {
                await using var store = database.CreateStore();
                await store.InitializeAsync();
            },
            output);
        _ = openSample;

        await using var store = database.CreateStore();
        await store.InitializeAsync();

        await PerfProbe.MeasureAsync($"[{rows}] Search recent limit=2000 (UI default)", 7, async () =>
            _ = await store.SearchAsync(new ClipboardHistoryQuery()), output);
        await PerfProbe.MeasureAsync($"[{rows}] Search recent limit=50", 7, async () =>
            _ = await store.SearchAsync(new ClipboardHistoryQuery(Limit: 50)), output);
        await PerfProbe.MeasureAsync($"[{rows}] Search recent limit=50 offset=5000", 7, async () =>
            _ = await store.SearchAsync(new ClipboardHistoryQuery(Limit: 50, Offset: Math.Min(5_000, rows - 100))), output);
        await PerfProbe.MeasureAsync($"[{rows}] Search text 'needle' (rare)", 7, async () =>
            _ = await store.SearchAsync(new ClipboardHistoryQuery("needle", Limit: 2000)), output);
        await PerfProbe.MeasureAsync($"[{rows}] Search text 'clip' (common) limit=2000", 7, async () =>
            _ = await store.SearchAsync(new ClipboardHistoryQuery("clip", Limit: 2000)), output);

        var hitHash = HashOf(ContentFor(rows / 2));
        await PerfProbe.MeasureAsync($"[{rows}] FindLiveContentByHash (hit)", 20, async () =>
            _ = await store.FindLiveContentByHashAsync(hitHash), output);
        await PerfProbe.MeasureAsync($"[{rows}] FindLiveContentByHash (miss)", 20, async () =>
            _ = await store.FindLiveContentByHashAsync(new string('0', 64)), output);

        await PerfProbe.MeasureAsync($"[{rows}] GetSyncableEvents range 200", 10, async () =>
            _ = await store.GetSyncableEventsAsync(PeerDeviceId, [new SequenceRange(1, 200)], 200), output);

        await PerfProbe.MeasureAsync($"[{rows}] GetOutboxBatch limit=64", 10, async () =>
            _ = await store.GetOutboxBatchAsync(PeerDeviceId, 64), output);

        await PerfProbe.MeasureAsync($"[{rows}] GetOutboxStatus", 10, async () =>
            _ = await store.GetOutboxStatusAsync(), output);

        await PerfProbe.MeasureAsync($"[{rows}] ApplyPeerAckRanges (1 origin, 1 range)", 10, async () =>
            await store.ApplyPeerAckRangesAsync(
                PeerDeviceId,
                [new OriginSequenceRanges(LocalDeviceId, [new SequenceRange(1, 64)])],
                BaseTime), output);

        var terminalSeq = rows / 4L;
        var terminalId = EventIdFor(terminalSeq, PeerDeviceId);
        await PerfProbe.MeasureAsync($"[{rows}] StoreRemoteTerminal deleted (tombstone upgrade)", 5, async () =>
        {
            await store.StoreRemoteTerminalAsync(
                new RemoteTerminalMarker(terminalId, PeerDeviceId, terminalSeq, ClipUnavailableReasons.Deleted),
                PeerDeviceId,
                BaseTime.AddDays(1));
            terminalSeq += 2;
            terminalId = EventIdFor(terminalSeq, PeerDeviceId);
        }, output);

        await PerfProbe.MeasureAsync($"[{rows}] DeleteAsync (local soft delete)", 5, async () =>
        {
            var target = await store.SearchAsync(new ClipboardHistoryQuery(Limit: 1, Offset: 10));
            await store.DeleteAsync(target[0].EventId, BaseTime.AddDays(1));
        }, output);

        await PerfProbe.MeasureAsync($"[{rows}] CleanupAsync (policy max 2000 entries)", 3, async () =>
            _ = await store.CleanupAsync(new ClipboardRetentionPolicy(2_000, TimeSpan.FromDays(3650)), BaseTime.AddDays(2)), output);

        var counter = 0;
        await PerfProbe.MeasureAsync($"[{rows}] StoreAsync x100 (local capture writes)", 3, async () =>
        {
            for (var index = 0; index < 100; index++)
            {
                counter++;
                var text = $"written-{counter}";
                await store.StoreAsync(new AcceptedClipboardContent(
                    text,
                    HashOf(text),
                    Encoding.UTF8.GetByteCount(text),
                    "bench",
                    BaseTime.AddDays(3).AddMilliseconds(counter)));
            }
        }, output);

        SqliteConnection.ClearAllPools();
        var size = new FileInfo(database.Path).Length;
        var wal = new FileInfo(database.Path + "-wal");
        output.WriteLine(string.Format(
            CultureInfo.InvariantCulture,
            "[{0}] db file {1:N0} bytes, wal {2:N0} bytes",
            rows,
            size,
            wal.Exists ? wal.Length : 0));

        await ExplainAsync(database.Path);
    }

    private async Task ExplainAsync(string path)
    {
        await using var connection = new SqliteConnection($"Data Source={path}");
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            EXPLAIN QUERY PLAN
            SELECT c.event_id
            FROM clips c
            LEFT JOIN clip_media m ON m.event_id = c.event_id
            LEFT JOIN media_blobs b ON b.content_hash = m.content_hash
            WHERE c.deleted_at IS NULL
              AND (c.expires_at IS NULL OR c.expires_at > 1)
              AND (NULL IS NULL OR c.content LIKE '%x%')
            ORDER BY c.created_at DESC, c.origin_seq DESC, c.origin_device_id ASC, c.event_id ASC
            LIMIT 50 OFFSET 0;
            """;
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            output.WriteLine("plan(search): " + reader.GetString(3));
        }
    }

    private static async Task<TimeSpan> SeedAsync(string path, int rows)
    {
        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        await using var connection = new SqliteConnection($"Data Source={path}");
        await connection.OpenAsync();
        await using var transaction = connection.BeginTransaction();
        await using var insert = connection.CreateCommand();
        insert.Transaction = transaction;
        insert.CommandText = """
            INSERT INTO clips (
                event_id, origin_device_id, origin_seq, kind, content, content_hash,
                source_app, created_at, expires_at, deleted_at, terminal_reason)
            VALUES ($event_id, $origin, $seq, 'text', $content, $hash, $source_app, $created_at, NULL, $deleted_at, $reason);
            """;
        var eventId = insert.Parameters.Add("$event_id", SqliteType.Text);
        var origin = insert.Parameters.Add("$origin", SqliteType.Text);
        var seq = insert.Parameters.Add("$seq", SqliteType.Integer);
        var content = insert.Parameters.Add("$content", SqliteType.Text);
        var hash = insert.Parameters.Add("$hash", SqliteType.Text);
        var sourceApp = insert.Parameters.Add("$source_app", SqliteType.Text);
        var createdAt = insert.Parameters.Add("$created_at", SqliteType.Integer);
        var deletedAt = insert.Parameters.Add("$deleted_at", SqliteType.Integer);
        var reason = insert.Parameters.Add("$reason", SqliteType.Text);

        var localSeq = 0L;
        var peerSeq = 0L;
        for (var index = 0; index < rows; index++)
        {
            var isLocal = index % 2 == 0;
            var originId = isLocal ? LocalDeviceId : PeerDeviceId;
            var sequence = isLocal ? ++localSeq : ++peerSeq;
            var text = ContentFor(index);
            var tombstone = index % 20 == 7;
            eventId.Value = EventIdFor(sequence, originId).ToString("D");
            origin.Value = originId;
            seq.Value = sequence;
            content.Value = tombstone ? string.Empty : text;
            hash.Value = tombstone ? string.Empty : HashOf(text);
            sourceApp.Value = index % 3 == 0 ? "notepad" : DBNull.Value;
            createdAt.Value = BaseTime.AddSeconds(index).ToUnixTimeMilliseconds();
            deletedAt.Value = tombstone ? BaseTime.AddSeconds(index + 1).ToUnixTimeMilliseconds() : DBNull.Value;
            reason.Value = tombstone ? "deleted" : DBNull.Value;
            await insert.ExecuteNonQueryAsync();
        }

        await using (var sequences = connection.CreateCommand())
        {
            sequences.Transaction = transaction;
            sequences.CommandText = """
                INSERT INTO local_sequences (device_id, next_seq) VALUES ($local, $next)
                ON CONFLICT(device_id) DO UPDATE SET next_seq = excluded.next_seq;
                INSERT INTO origin_receive_state (origin_device_id, contiguous_seq) VALUES ($local, $local_seq)
                ON CONFLICT(origin_device_id) DO UPDATE SET contiguous_seq = excluded.contiguous_seq;
                INSERT INTO origin_receive_state (origin_device_id, contiguous_seq) VALUES ($peer, $peer_seq)
                ON CONFLICT(origin_device_id) DO UPDATE SET contiguous_seq = excluded.contiguous_seq;
                """;
            sequences.Parameters.AddWithValue("$local", LocalDeviceId);
            sequences.Parameters.AddWithValue("$next", localSeq + 1);
            sequences.Parameters.AddWithValue("$local_seq", localSeq);
            sequences.Parameters.AddWithValue("$peer", PeerDeviceId);
            sequences.Parameters.AddWithValue("$peer_seq", peerSeq);
            await sequences.ExecuteNonQueryAsync();
        }

        // A realistic backlog: the newest 64 local rows still wait for the peer's ack.
        await using (var outbox = connection.CreateCommand())
        {
            outbox.Transaction = transaction;
            outbox.CommandText = """
                INSERT INTO outbox (peer_id, event_id, origin_device_id, origin_seq)
                SELECT $peer, event_id, origin_device_id, origin_seq FROM clips
                WHERE origin_device_id = $local
                ORDER BY origin_seq DESC LIMIT 64;
                """;
            outbox.Parameters.AddWithValue("$peer", PeerDeviceId);
            outbox.Parameters.AddWithValue("$local", LocalDeviceId);
            await outbox.ExecuteNonQueryAsync();
        }

        await transaction.CommitAsync();
        return stopwatch.Elapsed;
    }

    private static string ContentFor(int index)
    {
        var words = index % 97 == 0 ? "needle in the haystack " : "clip text sample ";
        return string.Create(CultureInfo.InvariantCulture, $"{words}{index} lorem ipsum dolor sit amet consectetur adipiscing elit {index * 7919}");
    }

    private static string HashOf(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private static Guid EventIdFor(long sequence, string origin)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(origin + ":" + sequence.ToString(CultureInfo.InvariantCulture)));
        return new Guid(bytes.AsSpan(0, 16));
    }

    private sealed class TemporaryDatabase : IAsyncDisposable
    {
        private readonly string directory = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            "clipsync-perf",
            Guid.NewGuid().ToString("N"));

        public TemporaryDatabase()
        {
            Directory.CreateDirectory(directory);
        }

        public string Path => System.IO.Path.Combine(directory, "history.db");

        public SqliteClipboardEventStore CreateStore() => new(Path, LocalDeviceId);

        public ValueTask DisposeAsync()
        {
            SqliteConnection.ClearAllPools();
            try
            {
                Directory.Delete(directory, recursive: true);
            }
            catch (IOException)
            {
            }

            return ValueTask.CompletedTask;
        }
    }
}
