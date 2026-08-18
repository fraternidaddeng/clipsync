using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.Tests.Storage;

public sealed class ClipboardImportTests
{
    private const string LocalDeviceId = "windows-local";
    private const string PhoneDeviceId = "22222222-2222-4222-8222-222222222222";
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

    [Fact]
    public async Task RoundTripExportImportRestoresEventIdContentAndCreated()
    {
        await using var sourceDb = new TemporaryDatabase("source");
        await using var destDb = new TemporaryDatabase("dest");
        await using var source = sourceDb.CreateStore();
        await using var dest = destDb.CreateStore();

        var first = await source.StoreAsync(Content("first-body", BaseTime));
        var second = await source.StoreAsync(Content("second-body", BaseTime.AddSeconds(1)));
        var jsonl = await ClipboardExport.EncodeJsonLinesAsync(source);

        var result = await ClipboardImport.ImportJsonLinesAsync(dest, jsonl);

        Assert.Equal(2, result.Imported);
        Assert.Equal(0, result.Skipped);

        var restored = await dest.SearchAsync(new ClipboardHistoryQuery());
        Assert.Equal(2, restored.Count);
        var byId = restored.ToDictionary(row => row.EventId);
        Assert.Equal("first-body", byId[first.EventId].Text);
        Assert.Equal(first.Content.CapturedAt, byId[first.EventId].CreatedAt);
        Assert.Equal("second-body", byId[second.EventId].Text);
        Assert.Equal(second.Content.CapturedAt, byId[second.EventId].CreatedAt);
    }

    [Fact]
    public async Task ReimportSkipsEveryRow()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.StoreAsync(Content("keep", BaseTime));
        var jsonl = await ClipboardExport.EncodeJsonLinesAsync(store);

        var first = await ClipboardImport.ImportJsonLinesAsync(store, jsonl);
        Assert.Equal(0, first.Imported);
        Assert.Equal(1, first.Skipped);

        var second = await ClipboardImport.ImportJsonLinesAsync(store, jsonl);
        Assert.Equal(0, second.Imported);
        Assert.Equal(1, second.Skipped);
        Assert.Single(await store.SearchAsync(new ClipboardHistoryQuery()));
    }

    [Fact]
    public async Task MalformedLineIsSkippedAndImportContinues()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        var good = EncodeRow(
            Guid.Parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "device-a",
            1,
            "good-body");
        var jsonl = good + "\nthis is not json\n" + EncodeRow(
            Guid.Parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            "device-a",
            2,
            "also-good");

        var result = await ClipboardImport.ImportJsonLinesAsync(store, jsonl);

        Assert.Equal(2, result.Imported);
        Assert.Equal(1, result.Skipped);
        var history = await store.SearchAsync(new ClipboardHistoryQuery());
        Assert.Equal(2, history.Count);
        Assert.Contains(history, row => row.Text == "good-body");
        Assert.Contains(history, row => row.Text == "also-good");
    }

    [Fact]
    public async Task ImportedRowsDoNotAppearInOutboxOrPendingSyncQueries()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PhoneDeviceId, "Phone", "android", "aa".PadLeft(64, 'a'), "secret"),
            BaseTime);

        var jsonl = EncodeRow(
            Guid.Parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            "foreign-device",
            7,
            "imported-local-only");

        var result = await ClipboardImport.ImportJsonLinesAsync(store, jsonl);
        Assert.Equal(new ClipboardImportResult(1, 0), result);

        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));
        Assert.Empty(await store.GetKnownVectorAsync());
        Assert.Empty(await store.GetPeerCursorsAsync(PhoneDeviceId));
        Assert.Equal(
            "imported-local-only",
            (await store.GetByIdAsync(Guid.Parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc")))!.Text);
    }

    [Fact]
    public async Task OversizedContentIsSkipped()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        var oversized = new string('x', ClipboardCapturePolicy.MaximumUtf8Bytes + 1);
        var jsonl = EncodeRow(
            Guid.Parse("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "device-a",
            1,
            oversized,
            contentHash: "not-checked-when-oversized");

        var result = await ClipboardImport.ImportJsonLinesAsync(store, jsonl);

        Assert.Equal(0, result.Imported);
        Assert.Equal(1, result.Skipped);
        Assert.Empty(await store.SearchAsync(new ClipboardHistoryQuery()));
    }

    [Fact]
    public void HeaderLineIsIgnoredWithoutCountingAsSkip()
    {
        var header = """{"format":"clipsync.export","format_version":1,"exported_at":0,"origin_device_id":"","platform":"windows","contains_plaintext_bodies":true}""";
        var parsed = ClipboardImport.ParseJsonLines(header + "\n" + EncodeRow(
            Guid.Parse("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            "device-a",
            1,
            "after-header"));

        Assert.Single(parsed.Rows);
        Assert.Equal(0, parsed.Skipped);
        Assert.Equal("after-header", parsed.Rows[0].Content);
    }

    [Fact]
    public void ParseJsonLinesIsPureAndHasNoLogSurface()
    {
        const string body = "user-clipboard-body-must-not-be-logged";
        var parsed = ClipboardImport.ParseJsonLines(EncodeRow(Guid.NewGuid(), "windows-local", 1, body));

        Assert.Equal(body, Assert.Single(parsed.Rows).Content);
        var methodNames = typeof(ClipboardImport).GetMethods().Select(method => method.Name);
        Assert.DoesNotContain(methodNames, name => name.Contains("log", StringComparison.OrdinalIgnoreCase));
    }

    private static string EncodeRow(
        Guid eventId,
        string originDeviceId,
        long originSeq,
        string content,
        string? contentHash = null) =>
        ClipboardExport.EncodeJsonLines(
        [
            new ClipboardHistoryEntry(
                eventId,
                originDeviceId,
                originSeq,
                content,
                contentHash ?? Hash(content),
                "notepad",
                BaseTime,
                null,
                null)
        ]);

    private static AcceptedClipboardContent Content(string text, DateTimeOffset capturedAt)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        return new AcceptedClipboardContent(text, Hash(text), bytes.Length, "notepad", capturedAt);
    }

    private static string Hash(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private sealed class TemporaryDatabase : IAsyncDisposable
    {
        private readonly string directory = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            "clipsync-tests",
            Guid.NewGuid().ToString("N"));

        public TemporaryDatabase(string? fileName = null)
        {
            Directory.CreateDirectory(directory);
            Path = System.IO.Path.Combine(directory, fileName is null ? "import.db" : $"{fileName}.db");
        }

        public string Path { get; }

        public SqliteClipboardEventStore CreateStore() => new(Path, LocalDeviceId);

        public ValueTask DisposeAsync()
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(directory, recursive: true);
            return ValueTask.CompletedTask;
        }
    }
}
