using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.Tests.Storage;

public sealed class ClipboardExportTests
{
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);
    private static readonly string[] ExpectedKeys =
    [
        "event_id",
        "origin_device_id",
        "origin_seq",
        "kind",
        "content",
        "content_hash",
        "source_app",
        "created_at",
        "expires_at"
    ];

    [Fact]
    public void EmptyListEncodesToEmptyString()
    {
        Assert.Equal(string.Empty, ClipboardExport.EncodeJsonLines([]));
    }

    [Fact]
    public void EncodeWritesOneObjectPerLineWithDeterministicKeyOrder()
    {
        var rows = new[]
        {
            Entry(Guid.Parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), "device-a", 1, "first"),
            Entry(Guid.Parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), "device-b", 2, "second")
        };

        var encoded = ClipboardExport.EncodeJsonLines(rows);
        var lines = encoded.TrimEnd('\n').Split('\n');
        Assert.Equal(2, lines.Length);

        foreach (var line in lines)
        {
            var positions = ExpectedKeys.Select(key => line.IndexOf($"\"{key}\"", StringComparison.Ordinal)).ToArray();
            Assert.All(positions, position => Assert.True(position >= 0));
            Assert.True(positions.Zip(positions.Skip(1), (left, right) => left < right).All(ordered => ordered));
        }
    }

    [Fact]
    public void UnicodeAndNewlinesInContentSurviveJsonParse()
    {
        const string body = "你好\nsecond line\t\"quoted\"";
        var row = new ClipboardHistoryEntry(
            Guid.Parse("11111111-1111-4111-8111-111111111111"),
            "windows-local",
            42,
            body,
            "abc",
            null,
            BaseTime,
            null,
            null);

        var encoded = ClipboardExport.EncodeJsonLines([row]);
        using var document = JsonDocument.Parse(encoded.TrimEnd('\n'));
        var root = document.RootElement;

        Assert.Equal(body, root.GetProperty("content").GetString());
        Assert.Equal("text", root.GetProperty("kind").GetString());
        Assert.Equal(JsonValueKind.Null, root.GetProperty("source_app").ValueKind);
        Assert.Equal(JsonValueKind.Null, root.GetProperty("expires_at").ValueKind);
        Assert.Equal(42, root.GetProperty("origin_seq").GetInt64());
        Assert.Equal(1_700_000_000_000, root.GetProperty("created_at").GetInt64());
    }

    [Fact]
    public void EncoderIsPureAndReturnsBodiesOnlyInTheResultString()
    {
        const string body = "user-clipboard-body-must-not-be-logged";
        var encoded = ClipboardExport.EncodeJsonLines([Entry(Guid.NewGuid(), "windows-local", 1, body)]);

        Assert.Contains(body, encoded, StringComparison.Ordinal);
        var methodNames = typeof(ClipboardExport).GetMethods().Select(method => method.Name);
        Assert.DoesNotContain(methodNames, name => name.Contains("log", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task EncodeFromStoreSearchMatchesJsonlFormat()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        var stored = await store.StoreAsync(Content("store-export-body", BaseTime));

        var encoded = await ClipboardExport.EncodeJsonLinesAsync(store);
        var lines = encoded.TrimEnd('\n').Split('\n');
        Assert.Single(lines);

        using var document = JsonDocument.Parse(lines[0]);
        var root = document.RootElement;
        Assert.Equal(stored.EventId.ToString("D"), root.GetProperty("event_id").GetString());
        Assert.Equal("windows-local", root.GetProperty("origin_device_id").GetString());
        Assert.Equal(1, root.GetProperty("origin_seq").GetInt64());
        Assert.Equal("text", root.GetProperty("kind").GetString());
        Assert.Equal("store-export-body", root.GetProperty("content").GetString());
        Assert.Equal(stored.Content.ContentHash, root.GetProperty("content_hash").GetString());
        Assert.Equal("notepad", root.GetProperty("source_app").GetString());
        Assert.Equal(BaseTime.ToUnixTimeMilliseconds(), root.GetProperty("created_at").GetInt64());
        Assert.Equal(JsonValueKind.Null, root.GetProperty("expires_at").ValueKind);
    }

    private static ClipboardHistoryEntry Entry(Guid eventId, string originDeviceId, long originSeq, string text) =>
        new(
            eventId,
            originDeviceId,
            originSeq,
            text,
            "hash",
            "notepad",
            BaseTime,
            BaseTime.AddHours(1),
            null);

    private static AcceptedClipboardContent Content(string text, DateTimeOffset capturedAt)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        return new AcceptedClipboardContent(
            text,
            Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(),
            bytes.Length,
            "notepad",
            capturedAt);
    }

    private sealed class TemporaryDatabase : IAsyncDisposable
    {
        private readonly string directory = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            "clipsync-tests",
            Guid.NewGuid().ToString("N"));

        public TemporaryDatabase()
        {
            Directory.CreateDirectory(directory);
        }

        public string Path => System.IO.Path.Combine(directory, "export.db");

        public SqliteClipboardEventStore CreateStore() => new(Path, "windows-local");

        public ValueTask DisposeAsync()
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(directory, recursive: true);
            return ValueTask.CompletedTask;
        }
    }
}
