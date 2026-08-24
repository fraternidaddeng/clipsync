using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.Tests.Storage;

/// <summary>
/// Export/import per docs/export-format-v1.md and docs/export-format-v2.md: JSON Lines out,
/// whole-file validation in, merge idempotent on (origin_device_id, origin_seq), no outbox
/// fan-out, the local sequence allocator always ahead of restored own-origin events, and
/// image records (v2) restored blob-and-all when the bytes are embedded.
/// </summary>
public sealed class HistoryTransferTests
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string OtherDeviceId = "44444444-4444-4444-8444-444444444444";
    private const string PhoneDeviceId = "22222222-2222-4222-8222-222222222222";
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

    [Fact]
    public async Task ExportWritesHeaderThenOneLinePerEventIncludingTombstones()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore(LocalDeviceId);
        await store.StoreAsync(Content("first", BaseTime));
        var second = await store.StoreAsync(Content("second", BaseTime.AddSeconds(1)));
        await store.DeleteAsync(second.EventId, BaseTime.AddSeconds(2));

        var document = await ExportAsync(store);
        var lines = document.TrimEnd('\n').Split('\n');

        Assert.Equal(3, lines.Length);
        var header = HistoryExportFormat.ParseHeaderLine(lines[0]);
        Assert.Equal(HistoryExportFormat.TextOnlyFormatVersion, header.FormatVersion);
        Assert.Equal(2, header.EventCount);
        Assert.Equal(LocalDeviceId, header.ExportingDeviceId);
        Assert.Equal("windows", header.Platform);

        var live = HistoryExportFormat.ParseClipLine(lines[1], 2);
        Assert.Equal("first", live.Content);
        Assert.Equal(Hash("first"), live.ContentHash);
        Assert.Equal("notepad", live.SourceApp);
        Assert.Null(live.TerminalReason);

        var tombstone = HistoryExportFormat.ParseClipLine(lines[2], 3);
        Assert.True(tombstone.IsTerminal);
        Assert.Equal("deleted", tombstone.TerminalReason);
        Assert.Null(tombstone.Content);
        Assert.NotNull(tombstone.DeletedAtMs);
    }

    [Fact]
    public async Task ExportedFileNeverContainsSecretsOrDeviceRows()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore(LocalDeviceId);
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PhoneDeviceId, "Phone", "android", "ff".PadLeft(64, 'f'), "protected-secret-phone"),
            BaseTime);
        await store.StoreAsync(Content("visible clip", BaseTime));

        var document = await ExportAsync(store);

        Assert.Contains("visible clip", document, StringComparison.Ordinal);
        Assert.DoesNotContain("protected-secret-phone", document, StringComparison.Ordinal);
        Assert.DoesNotContain(PhoneDeviceId, document, StringComparison.Ordinal);
        Assert.DoesNotContain("f".PadLeft(64, 'f'), document, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ImportIntoEmptyStoreRestoresHistoryAndReceiveVector()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreAsync(Content("own one", BaseTime));
        await source.StoreAsync(Content("own two", BaseTime.AddSeconds(1)));
        await source.StoreRemoteEventAsync(RemoteEvent("phone one", 1), sourcePeerId: null);
        var document = await ExportAsync(source);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        var result = await ImportAsync(target, document);

        Assert.Equal(new HistoryImportResult(3, 0, 0), result);
        var history = await target.SearchAsync(new ClipboardHistoryQuery());
        Assert.Equal(3, history.Count);
        Assert.Contains(history, entry => entry.Text == "own one");
        Assert.Contains(history, entry => entry.Text == "phone one");

        var vector = await target.GetKnownVectorAsync();
        Assert.Equal(2, vector[LocalDeviceId].ContiguousSeq);
        Assert.Equal(1, vector[PhoneDeviceId].ContiguousSeq);
    }

    [Fact]
    public async Task ImportingTheSameFileTwiceChangesNothing()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreAsync(Content("repeat me", BaseTime));
        var document = await ExportAsync(source);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        Assert.Equal(new HistoryImportResult(1, 0, 0), await ImportAsync(target, document));
        Assert.Equal(new HistoryImportResult(0, 1, 0), await ImportAsync(target, document));

        Assert.Single(await target.SearchAsync(new ClipboardHistoryQuery()));
    }

    [Fact]
    public async Task ImportMergesOnlyTheEventsTheTargetIsMissing()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        var sharedEvent = RemoteEvent("already synced", 1);
        await source.StoreRemoteEventAsync(sharedEvent, sourcePeerId: null);
        await source.StoreRemoteEventAsync(RemoteEvent("missing on target", 2), sourcePeerId: null);
        var document = await ExportAsync(source);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        await target.StoreRemoteEventAsync(sharedEvent, sourcePeerId: null);

        var result = await ImportAsync(target, document);

        Assert.Equal(new HistoryImportResult(1, 1, 0), result);
        Assert.Equal(2, (await target.SearchAsync(new ClipboardHistoryQuery())).Count);
        Assert.Equal(2, (await target.GetKnownVectorAsync())[PhoneDeviceId].ContiguousSeq);
    }

    [Fact]
    public async Task RestoringOwnEventsBumpsTheSequenceAllocatorPastTheImport()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        for (var index = 0; index < 3; index++)
        {
            await source.StoreAsync(Content($"own {index}", BaseTime.AddSeconds(index)));
        }

        var document = await ExportAsync(source);

        // The restore case: a fresh install that kept its device identity.
        await using var restoredDatabase = new TemporaryDatabase();
        await using var restored = restoredDatabase.CreateStore(LocalDeviceId);
        var result = await ImportAsync(restored, document);
        Assert.Equal(new HistoryImportResult(3, 0, 0), result);

        var next = await restored.StoreAsync(Content("after restore", BaseTime.AddMinutes(1)));
        Assert.Equal(4, next.OriginSequence);
    }

    [Fact]
    public async Task ImportedTombstoneNeverErasesALiveLocalRowAndViceVersa()
    {
        // Source deleted the event; target still holds it live.
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        var shared = RemoteEvent("contested", 1);
        await source.StoreRemoteEventAsync(shared, sourcePeerId: null);
        await source.DeleteAsync(shared.EventId, BaseTime.AddSeconds(1));
        var tombstoneDocument = await ExportAsync(source);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        await target.StoreRemoteEventAsync(shared, sourcePeerId: null);

        Assert.Equal(new HistoryImportResult(0, 1, 0), await ImportAsync(target, tombstoneDocument));
        var entry = Assert.Single(await target.SearchAsync(new ClipboardHistoryQuery()));
        Assert.Equal("contested", entry.Text);

        // The mirror image: the target deleted it; a live import must not revive it.
        await using var deleterDatabase = new TemporaryDatabase();
        await using var deleter = deleterDatabase.CreateStore(OtherDeviceId);
        await deleter.StoreRemoteEventAsync(shared, sourcePeerId: null);
        var liveDocument = await ExportAsync(deleter);

        await target.DeleteAsync(shared.EventId, BaseTime.AddSeconds(2));
        Assert.Equal(new HistoryImportResult(0, 1, 0), await ImportAsync(target, liveDocument));
        Assert.Empty(await target.SearchAsync(new ClipboardHistoryQuery()));
    }

    [Fact]
    public async Task ConflictingIdentitiesAreCountedAndLeaveTheStoreUntouched()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreRemoteEventAsync(RemoteEvent("their version", 1), sourcePeerId: null);
        await source.StoreRemoteEventAsync(RemoteEvent("clean", 2), sourcePeerId: null);
        var document = await ExportAsync(source);

        // The target holds a different event under the same (origin, seq) key.
        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        await target.StoreRemoteEventAsync(RemoteEvent("our version", 1), sourcePeerId: null);

        var result = await ImportAsync(target, document);

        Assert.Equal(new HistoryImportResult(1, 0, 1), result);
        var history = await target.SearchAsync(new ClipboardHistoryQuery());
        Assert.Contains(history, entry => entry.Text == "our version");
        Assert.Contains(history, entry => entry.Text == "clean");
        Assert.DoesNotContain(history, entry => entry.Text == "their version");
    }

    [Fact]
    public async Task ImportNeverEnqueuesOutboxRows()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreRemoteEventAsync(RemoteEvent("no fan-out", 1), sourcePeerId: null);
        var document = await ExportAsync(source);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        await target.UpsertDeviceAsync(
            new NewPairedDevice(LocalDeviceId, "Desk", "windows", "aa".PadLeft(64, 'a'), "secret"),
            BaseTime);

        await ImportAsync(target, document);

        Assert.Empty(await target.GetOutboxBatchAsync(LocalDeviceId, 10));
        Assert.Equal(0, (await target.GetOutboxStatusAsync()).PendingCount);
    }

    [Fact]
    public async Task TamperedContentFailsTheWholeFileBeforeAnyWrite()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreAsync(Content("intact", BaseTime));
        await source.StoreAsync(Content("tampered", BaseTime.AddSeconds(1)));
        var document = (await ExportAsync(source)).Replace("tampered", "attacker", StringComparison.Ordinal);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        var exception = await Assert.ThrowsAsync<HistoryTransferException>(() => ImportAsync(target, document).AsTask());

        Assert.Equal(HistoryTransferErrorCodes.HashMismatch, exception.ErrorCode);
        Assert.Empty(await target.SearchAsync(new ClipboardHistoryQuery()));
    }

    [Fact]
    public async Task UnsupportedVersionsAndForeignFilesAreRejectedWithStableCodes()
    {
        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);

        var wrongVersion =
            """{"type":"header","format":"clipsync-history","format_version":3,"exported_at_ms":1,"exporting_device_id":"x","platform":"windows","event_count":0}"""
            + "\n";
        var versionError = await Assert.ThrowsAsync<HistoryTransferException>(
            () => ImportAsync(target, wrongVersion).AsTask());
        Assert.Equal(HistoryTransferErrorCodes.UnsupportedVersion, versionError.ErrorCode);

        var foreignError = await Assert.ThrowsAsync<HistoryTransferException>(
            () => ImportAsync(target, "{\"hello\":\"world\"}\n").AsTask());
        Assert.Equal(HistoryTransferErrorCodes.BadHeader, foreignError.ErrorCode);

        var emptyError = await Assert.ThrowsAsync<HistoryTransferException>(
            () => ImportAsync(target, string.Empty).AsTask());
        Assert.Equal(HistoryTransferErrorCodes.BadHeader, emptyError.ErrorCode);
    }

    [Fact]
    public async Task TruncatedFilesFailTheEventCountCheck()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreAsync(Content("kept", BaseTime));
        await source.StoreAsync(Content("lost in truncation", BaseTime.AddSeconds(1)));
        var lines = (await ExportAsync(source)).TrimEnd('\n').Split('\n');
        var truncated = string.Join('\n', lines[..^1]) + "\n";

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        var exception = await Assert.ThrowsAsync<HistoryTransferException>(
            () => ImportAsync(target, truncated).AsTask());

        Assert.Equal(HistoryTransferErrorCodes.CountMismatch, exception.ErrorCode);
        Assert.Empty(await target.SearchAsync(new ClipboardHistoryQuery()));
    }

    [Fact]
    public async Task BlankLinesAreToleratedAroundRecords()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreAsync(Content("padded", BaseTime));
        var document = "\n" + (await ExportAsync(source)) + "\n\n";

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        Assert.Equal(new HistoryImportResult(1, 0, 0), await ImportAsync(target, document));
    }

    // ---- Image records (docs/export-format-v2.md) ----

    [Fact]
    public async Task ImageEventsExportAsFormatVersion2WithEmbeddedBytes()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore(LocalDeviceId);
        await store.StoreAsync(Content("text before image", BaseTime));
        var (png, hash) = TinyPng(255, 0, 0);
        await store.StoreImageAsync(Image(png, hash, BaseTime.AddSeconds(1)));

        var lines = (await ExportAsync(store)).TrimEnd('\n').Split('\n');

        var header = HistoryExportFormat.ParseHeaderLine(lines[0]);
        Assert.Equal(HistoryExportFormat.FormatVersion, header.FormatVersion);
        Assert.Equal(2, header.EventCount);

        var image = HistoryExportFormat.ParseClipLine(lines[2], 3, header.FormatVersion);
        Assert.Equal("image", image.Kind);
        Assert.Null(image.Content);
        Assert.Equal(hash, image.ContentHash);
        Assert.NotNull(image.Media);
        Assert.Equal(MediaLimits.MimePng, image.Media!.MimeType);
        Assert.Equal(png.Length, image.Media.EncodedBytes);
        Assert.Equal(1, image.Media.PixelWidth);
        Assert.Equal(1, image.Media.PixelHeight);
        Assert.Equal(png, image.Media.EncodedData);
    }

    [Fact]
    public async Task ImportRestoresImageEventsBlobAndAllAndStaysIdempotent()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        await source.StoreAsync(Content("alongside", BaseTime));
        var (png, hash) = TinyPng(0, 128, 255);
        await source.StoreImageAsync(Image(png, hash, BaseTime.AddSeconds(1)));
        var document = await ExportAsync(source);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        Assert.Equal(new HistoryImportResult(2, 0, 0), await ImportAsync(target, document));
        Assert.Equal(new HistoryImportResult(0, 2, 0), await ImportAsync(target, document));

        var history = await target.SearchAsync(new ClipboardHistoryQuery());
        var imageEntry = Assert.Single(history, entry => entry.Kind == "image");
        Assert.Equal(hash, imageEntry.ContentHash);
        Assert.Equal(MediaLimits.MimePng, imageEntry.MimeType);
        Assert.Equal(png.Length, imageEntry.EncodedBytes);
        Assert.True(target.Media.Exists(hash));
        Assert.Equal(png, target.Media.ReadAllBytes(hash));
        Assert.Equal(2, (await target.GetKnownVectorAsync())[LocalDeviceId].ContiguousSeq);
    }

    [Fact]
    public async Task ImageTombstonesRoundTripWithoutResurrectingBytes()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        var (png, hash) = TinyPng(9, 9, 9);
        var stored = await source.StoreImageAsync(Image(png, hash, BaseTime));
        await source.DeleteAsync(stored.EventId, BaseTime.AddSeconds(1));
        var document = await ExportAsync(source);

        var lines = document.TrimEnd('\n').Split('\n');
        var tombstone = HistoryExportFormat.ParseClipLine(lines[1], 2, HistoryExportFormat.FormatVersion);
        Assert.Equal("image", tombstone.Kind);
        Assert.True(tombstone.IsTerminal);
        Assert.Null(tombstone.ContentHash);
        Assert.Null(tombstone.Media);
        Assert.DoesNotContain(hash, document, StringComparison.Ordinal);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        Assert.Equal(new HistoryImportResult(1, 0, 0), await ImportAsync(target, document));
        Assert.Empty(await target.SearchAsync(new ClipboardHistoryQuery()));
        Assert.False(target.Media.Exists(hash));
    }

    [Fact]
    public async Task MissingBlobExportsMetadataOnlyAndImportsAsMissingMedia()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        var (png, hash) = TinyPng(1, 2, 3);
        await source.StoreImageAsync(Image(png, hash, BaseTime));
        source.Media.DeleteBlob(hash);
        var document = await ExportAsync(source);

        var lines = document.TrimEnd('\n').Split('\n');
        var record = HistoryExportFormat.ParseClipLine(lines[1], 2, HistoryExportFormat.FormatVersion);
        Assert.NotNull(record.Media);
        Assert.Null(record.Media!.EncodedData);
        Assert.Equal(png.Length, record.Media.EncodedBytes);

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        Assert.Equal(new HistoryImportResult(1, 0, 0), await ImportAsync(target, document));

        var entry = Assert.Single(await target.SearchAsync(new ClipboardHistoryQuery()));
        Assert.Equal("image", entry.Kind);
        Assert.Equal(MediaLimits.MimePng, entry.MimeType);
        Assert.False(target.Media.Exists(hash));
    }

    [Fact]
    public async Task TamperedImageBytesFailTheWholeFileBeforeAnyWrite()
    {
        await using var sourceDatabase = new TemporaryDatabase();
        await using var source = sourceDatabase.CreateStore(LocalDeviceId);
        var (png, hash) = TinyPng(200, 100, 50);
        await source.StoreImageAsync(Image(png, hash, BaseTime));
        var document = await ExportAsync(source);

        var marker = "\"data_base64\":\"";
        var start = document.IndexOf(marker, StringComparison.Ordinal) + marker.Length;
        var flipped = document[start + 20] == 'A' ? 'B' : 'A';
        var tampered = document[..(start + 20)] + flipped + document[(start + 21)..];

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        var exception = await Assert.ThrowsAsync<HistoryTransferException>(
            () => ImportAsync(target, tampered).AsTask());

        Assert.Equal(HistoryTransferErrorCodes.HashMismatch, exception.ErrorCode);
        Assert.Empty(await target.SearchAsync(new ClipboardHistoryQuery()));
        Assert.False(target.Media.Exists(hash));
    }

    [Fact]
    public async Task ImageRecordsInsideAVersion1FileAreRejected()
    {
        var document =
            """{"type":"header","format":"clipsync-history","format_version":1,"exported_at_ms":1,"exporting_device_id":"x","platform":"windows","event_count":1}"""
            + "\n"
            + """{"type":"clip","event_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","origin_device_id":"x","origin_seq":1,"kind":"image","content":null,"content_hash":null,"source_app":null,"created_at_ms":1,"expires_at_ms":null,"deleted_at_ms":1,"terminal_reason":"deleted"}"""
            + "\n";

        await using var targetDatabase = new TemporaryDatabase();
        await using var target = targetDatabase.CreateStore(OtherDeviceId);
        var exception = await Assert.ThrowsAsync<HistoryTransferException>(
            () => ImportAsync(target, document).AsTask());

        Assert.Equal(HistoryTransferErrorCodes.MalformedRecord, exception.ErrorCode);
        Assert.Empty(await target.SearchAsync(new ClipboardHistoryQuery()));
    }

    private static async Task<string> ExportAsync(SqliteClipboardEventStore store)
    {
        await using var writer = new StringWriter();
        await store.ExportHistoryAsync(writer, BaseTime.AddDays(1));
        return writer.ToString();
    }

    private static async ValueTask<HistoryImportResult> ImportAsync(SqliteClipboardEventStore store, string document)
    {
        using var reader = new StringReader(document);
        return await store.ImportHistoryAsync(reader);
    }

    private static RemoteClipEvent RemoteEvent(string content, long seq) =>
        new(Guid.NewGuid(), PhoneDeviceId, seq, content, Hash(content), "phone-app", BaseTime, null);

    private static string Hash(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private static AcceptedClipboardContent Content(string text, DateTimeOffset capturedAt)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        return new AcceptedClipboardContent(text, Hash(text), bytes.Length, "notepad", capturedAt);
    }

    private static (byte[] Png, string Hash) TinyPng(byte blue, byte green, byte red)
    {
        var png = ImageCodec.EncodePngBgra(1, 1, [blue, green, red, 255]);
        return (png, ImageCodec.HashBytes(png));
    }

    private static AcceptedImageContent Image(byte[] png, string hash, DateTimeOffset capturedAt) =>
        new(png, hash, MediaLimits.MimePng, 1, 1, "paint", capturedAt);

    private sealed class TemporaryDatabase : IAsyncDisposable
    {
        private readonly string directory = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            "clipsync-transfer-tests",
            Guid.NewGuid().ToString("N"));

        public TemporaryDatabase()
        {
            Directory.CreateDirectory(directory);
        }

        public SqliteClipboardEventStore CreateStore(string deviceId) =>
            new(System.IO.Path.Combine(directory, "transfer.db"), deviceId);

        public ValueTask DisposeAsync()
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(directory, recursive: true);
            return ValueTask.CompletedTask;
        }
    }
}
