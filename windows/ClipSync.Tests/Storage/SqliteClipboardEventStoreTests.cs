using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.Tests.Storage;

public sealed class SqliteClipboardEventStoreTests
{
    private const string LocalDeviceId = "windows-local";
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);
    private static readonly string[] ReopenedHistory = ["second", "first"];
    private static readonly string[] RetainedHistory = ["keep-newest", "keep-older"];

    [Fact]
    public async Task InitializeCreatesVersionedWalDatabaseWithForeignKeysEnabled()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();

        await store.InitializeAsync();

        var state = await store.ReadDatabaseStateAsync();
        Assert.Equal("wal", state.JournalMode, ignoreCase: true);
        Assert.True(state.ForeignKeysEnabled);
        Assert.Equal(SqliteClipboardEventStore.SchemaVersion, state.SchemaVersion);
        Assert.Equal(2, SqliteClipboardEventStore.SchemaVersion);
    }

    [Fact]
    public async Task ReinitializeOnExistingFileIsIdempotentAndKeepsRows()
    {
        await using var database = new TemporaryDatabase();
        Guid eventId;
        await using (var first = database.CreateStore())
        {
            await first.InitializeAsync();
            eventId = (await first.StoreAsync(Content("keep-across-reinit", BaseTime))).EventId;
            Assert.Equal(SqliteClipboardEventStore.SchemaVersion, (await first.ReadDatabaseStateAsync()).SchemaVersion);
        }

        await using var reopened = database.CreateStore();
        await reopened.InitializeAsync();
        await reopened.InitializeAsync();

        var state = await reopened.ReadDatabaseStateAsync();
        Assert.Equal(SqliteClipboardEventStore.SchemaVersion, state.SchemaVersion);
        var history = await reopened.SearchAsync(new ClipboardHistoryQuery());
        Assert.Single(history);
        Assert.Equal(eventId, history[0].EventId);
        Assert.Equal("keep-across-reinit", history[0].Text);
        Assert.Equal(1, history[0].OriginSequence);
    }

    [Fact]
    public async Task StoreAllocatesMonotonicSequencesAndSurvivesReopen()
    {
        await using var database = new TemporaryDatabase();
        Guid firstEventId;

        await using (var firstStore = database.CreateStore())
        {
            var first = await firstStore.StoreAsync(Content("first", BaseTime));
            var second = await firstStore.StoreAsync(Content("second", BaseTime.AddMilliseconds(1)));

            firstEventId = first.EventId;
            Assert.Equal(1, first.OriginSequence);
            Assert.Equal(2, second.OriginSequence);
        }

        await using var reopened = database.CreateStore();
        var history = await reopened.SearchAsync(new ClipboardHistoryQuery());

        Assert.Equal(ReopenedHistory, history.Select(item => item.Text));
        Assert.Contains(history, item => item.EventId == firstEventId && item.OriginDeviceId == LocalDeviceId);
    }

    [Fact]
    public async Task ConcurrentStoresAllocateEachSequenceExactlyOnce()
    {
        await using var database = new TemporaryDatabase();
        await using var firstStore = database.CreateStore();
        await using var secondStore = database.CreateStore();

        var writes = Enumerable.Range(0, 40)
            .Select(index => (index % 2 == 0 ? firstStore : secondStore)
                .StoreAsync(Content($"clip-{index}", BaseTime.AddMilliseconds(index)))
                .AsTask());

        var stored = await Task.WhenAll(writes);

        Assert.Equal(Enumerable.Range(1, 40).Select(value => (long)value), stored.Select(item => item.OriginSequence).Order());
        Assert.Equal(40, stored.Select(item => item.EventId).Distinct().Count());
    }

    [Fact]
    public async Task FailureAfterSequenceAllocationRollsBackClipAndSequence()
    {
        await using var database = new TemporaryDatabase();
        var injector = new ThrowOnceFaultInjector(StorageFaultPoint.AfterSequenceAllocated);
        await using var store = database.CreateStore(injector);

        await Assert.ThrowsAsync<InjectedStorageException>(
            () => store.StoreAsync(Content("must roll back", BaseTime)).AsTask());

        Assert.Empty(await store.SearchAsync(new ClipboardHistoryQuery()));
        var next = await store.StoreAsync(Content("committed", BaseTime.AddSeconds(1)));
        Assert.Equal(1, next.OriginSequence);
    }

    [Fact]
    public async Task FailureBeforeCommitRollsBackClipAndSequence()
    {
        await using var database = new TemporaryDatabase();
        var injector = new ThrowOnceFaultInjector(StorageFaultPoint.BeforeCommit);
        await using var store = database.CreateStore(injector);

        await Assert.ThrowsAsync<InjectedStorageException>(
            () => store.StoreAsync(Content("must roll back", BaseTime)).AsTask());

        Assert.Empty(await store.SearchAsync(new ClipboardHistoryQuery()));
        var next = await store.StoreAsync(Content("committed", BaseTime.AddSeconds(1)));
        Assert.Equal(1, next.OriginSequence);
    }

    [Fact]
    public async Task SearchIsLiteralParameterizedAndExcludesSoftDeletedRows()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        var literal = await store.StoreAsync(Content("value 100%_literal", BaseTime));
        await store.StoreAsync(Content("unrelated", BaseTime.AddSeconds(1)));
        await store.StoreAsync(Content("%' OR 1=1 -- appears literally", BaseTime.AddSeconds(2)));

        var wildcardSearch = await store.SearchAsync(new ClipboardHistoryQuery("100%_literal"));
        var injectionSearch = await store.SearchAsync(new ClipboardHistoryQuery("%' OR 1=1 --"));
        Assert.Single(wildcardSearch);
        Assert.Equal(literal.EventId, wildcardSearch[0].EventId);
        Assert.Single(injectionSearch);

        Assert.True(await store.DeleteAsync(literal.EventId, BaseTime.AddDays(1)));
        Assert.Empty(await store.SearchAsync(new ClipboardHistoryQuery("100%_literal")));

        var deleted = await store.GetByIdAsync(literal.EventId, includeDeleted: true);
        Assert.NotNull(deleted);
        Assert.True(deleted.IsDeleted);
        Assert.Equal(string.Empty, deleted.Text);
        Assert.Equal(string.Empty, deleted.ContentHash);
        Assert.Null(deleted.SourceProcess);
    }

    [Fact]
    public async Task ClearSoftDeletesEveryVisibleEntry()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        var first = await store.StoreAsync(Content("first", BaseTime));
        var second = await store.StoreAsync(Content("second", BaseTime.AddSeconds(1)));

        var cleared = await store.ClearAsync(BaseTime.AddDays(1));

        Assert.Equal(2, cleared);
        Assert.Empty(await store.SearchAsync(new ClipboardHistoryQuery()));
        Assert.True((await store.GetByIdAsync(first.EventId, includeDeleted: true))!.IsDeleted);
        Assert.True((await store.GetByIdAsync(second.EventId, includeDeleted: true))!.IsDeleted);
    }

    [Fact]
    public async Task CleanupAppliesAgeAndCountLimitsInOnePass()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.StoreAsync(Content("expired-by-age", BaseTime.AddDays(-31)));
        await store.StoreAsync(Content("trimmed-by-count", BaseTime.AddDays(-2)));
        await store.StoreAsync(Content("keep-older", BaseTime.AddDays(-1)));
        await store.StoreAsync(Content("keep-newest", BaseTime));

        var removed = await store.CleanupAsync(
            new ClipboardRetentionPolicy(maximumEntries: 2, maximumAge: TimeSpan.FromDays(30)),
            BaseTime);

        var remaining = await store.SearchAsync(new ClipboardHistoryQuery());
        Assert.Equal(2, removed);
        Assert.Equal(RetainedHistory, remaining.Select(item => item.Text));
    }

    [Fact]
    public async Task SettingsUseParameterizedUpsertAndRoundTripAfterReopen()
    {
        await using var database = new TemporaryDatabase();
        const string hostileKey = "setting'); DROP TABLE clips; --";

        await using (var store = database.CreateStore())
        {
            await store.SetSettingAsync(hostileKey, "line 1\nline 2");
            await store.SetSettingAsync("retention_days", "30");
            await store.SetSettingAsync("retention_days", "45");

            Assert.Equal("line 1\nline 2", await store.GetSettingAsync(hostileKey));
            Assert.Equal("45", await store.GetSettingAsync("retention_days"));
            Assert.Null(await store.GetSettingAsync("missing"));
            Assert.Equal(1, (await store.StoreAsync(Content("clips table remains", BaseTime))).OriginSequence);
        }

        await using var reopened = database.CreateStore();
        Assert.Equal("line 1\nline 2", await reopened.GetSettingAsync(hostileKey));
    }

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

    private sealed class ThrowOnceFaultInjector(StorageFaultPoint target) : IStorageFaultInjector
    {
        private int pending = 1;

        public ValueTask InjectAsync(StorageFaultPoint point, CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (point == target && Interlocked.Exchange(ref pending, 0) == 1)
            {
                throw new InjectedStorageException();
            }

            return ValueTask.CompletedTask;
        }
    }

    private sealed class InjectedStorageException : Exception;

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

        public string Path => System.IO.Path.Combine(directory, "history.db");

        public SqliteClipboardEventStore CreateStore(IStorageFaultInjector? faultInjector = null) =>
            new(Path, LocalDeviceId, faultInjector);

        public ValueTask DisposeAsync()
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(directory, recursive: true);
            return ValueTask.CompletedTask;
        }
    }
}
