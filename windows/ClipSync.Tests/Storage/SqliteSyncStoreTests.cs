using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using ClipSync.Core.Sync;
using Microsoft.Data.Sqlite;

namespace ClipSync.Tests.Storage;

public sealed class SqliteSyncStoreTests
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string PhoneDeviceId = "22222222-2222-4222-8222-222222222222";
    private const string TabletDeviceId = "33333333-3333-4333-8333-333333333333";
    private const string GhostDeviceId = "44444444-4444-4444-8444-444444444444";
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

    [Fact]
    public async Task VersionOneDatabaseMigratesToVersionTwoKeepingData()
    {
        await using var database = new TemporaryDatabase();
        await CreateVersionOneDatabaseAsync(database.Path);

        await using var store = database.CreateStore();
        await store.InitializeAsync();

        var state = await store.ReadDatabaseStateAsync();
        Assert.Equal(SqliteClipboardEventStore.SchemaVersion, state.SchemaVersion);

        var history = await store.SearchAsync(new ClipboardHistoryQuery());
        Assert.Single(history);
        Assert.Equal("legacy content", history[0].Text);

        // Receive state is backfilled from local_sequences during migration.
        var vector = await store.GetKnownVectorAsync();
        Assert.Equal(1, vector[LocalDeviceId].ContiguousSeq);

        // The next local capture continues the sequence and lands in the receive state.
        var stored = await store.StoreAsync(Content("post-migration", BaseTime.AddSeconds(1)));
        Assert.Equal(2, stored.OriginSequence);
        Assert.Equal(2, (await store.GetKnownVectorAsync())[LocalDeviceId].ContiguousSeq);
    }

    [Fact]
    public async Task LocalCaptureEnqueuesOutboxForActivePeersOnly()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        await store.UpsertDeviceAsync(Tablet(), BaseTime);
        await store.RevokeDeviceAsync(TabletDeviceId, BaseTime.AddSeconds(1));

        var stored = await store.StoreAsync(Content("hello", BaseTime.AddSeconds(2)));

        var phoneBatch = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        var tabletBatch = await store.GetOutboxBatchAsync(TabletDeviceId, 10);
        Assert.Single(phoneBatch);
        Assert.Empty(tabletBatch);
        Assert.Equal(stored.EventId, phoneBatch[0].Entry.EventId);
        Assert.Equal(LocalDeviceId, phoneBatch[0].Entry.OriginDeviceId);
        Assert.Equal("hello", phoneBatch[0].Event.Content);
        Assert.False(phoneBatch[0].Event.IsTerminal);
    }

    [Fact]
    public async Task RemoteEventOutOfOrderKeepsContiguousCursorBelowGap()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);

        for (long seq = 1; seq <= 10; seq++)
        {
            Assert.IsType<RemoteStoreResult.Stored>(
                await store.StoreRemoteEventAsync(RemoteEvent($"clip {seq}", seq), PhoneDeviceId));
        }

        // The acceptance case: sequence 12 arrives while 11 is missing.
        var afterTwelve = Assert.IsType<RemoteStoreResult.Stored>(
            await store.StoreRemoteEventAsync(RemoteEvent("clip 12", 12), PhoneDeviceId));
        Assert.Equal(10, afterTwelve.ReceiveState.ContiguousSeq);
        Assert.Equal(new[] { new SequenceRange(12, 12) }, afterTwelve.ReceiveState.ReceivedRanges);

        var afterEleven = Assert.IsType<RemoteStoreResult.Stored>(
            await store.StoreRemoteEventAsync(RemoteEvent("clip 11", 11), PhoneDeviceId));
        Assert.Equal(12, afterEleven.ReceiveState.ContiguousSeq);
        Assert.Empty(afterEleven.ReceiveState.ReceivedRanges);
    }

    [Fact]
    public async Task RemoteEventReplayIsIdempotentAndConflictsAreDetected()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);

        var original = RemoteEvent("original", 1);
        Assert.IsType<RemoteStoreResult.Stored>(await store.StoreRemoteEventAsync(original, PhoneDeviceId));

        Assert.IsType<RemoteStoreResult.AlreadyPersisted>(await store.StoreRemoteEventAsync(original, PhoneDeviceId));
        Assert.True((await store.GetKnownVectorAsync())[PhoneDeviceId].Contains(1));

        // Same idempotency key with a different event identity.
        Assert.IsType<RemoteStoreResult.IdentityConflict>(await store.StoreRemoteEventAsync(
            original with { EventId = Guid.NewGuid() },
            PhoneDeviceId));

        // Same idempotency key and event id but different content.
        Assert.IsType<RemoteStoreResult.IdentityConflict>(await store.StoreRemoteEventAsync(
            original with { Content = "tampered", ContentHash = Hash("tampered") },
            PhoneDeviceId));

        // Same event id reused for another sequence.
        Assert.IsType<RemoteStoreResult.IdentityConflict>(await store.StoreRemoteEventAsync(
            original with { OriginSeq = 2 },
            PhoneDeviceId));
    }

    [Fact]
    public async Task CleanupDoesNotExpireClipsStillWaitingInOutbox()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);

        for (var index = 0; index < 5; index++)
        {
            await store.StoreAsync(Content($"queued-{index}", BaseTime.AddSeconds(index)));
        }

        var removed = await store.CleanupAsync(
            new ClipboardRetentionPolicy(maximumEntries: 2, maximumAge: TimeSpan.FromDays(30)),
            BaseTime.AddDays(1));

        Assert.Equal(0, removed);
        var batch = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        Assert.Equal(5, batch.Count);
        Assert.All(batch, row => Assert.False(string.IsNullOrEmpty(row.Event.Content)));
        Assert.Equal(5, (await store.SearchAsync(new ClipboardHistoryQuery())).Count);
    }

    [Fact]
    public async Task AlreadyPersistedRestoresReceiveCoverageAfterStateLoss()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        var original = RemoteEvent("original", 1);
        Assert.IsType<RemoteStoreResult.Stored>(await store.StoreRemoteEventAsync(original, PhoneDeviceId));

        await using (var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = database.Path
        }.ToString()))
        {
            await connection.OpenAsync();
            await using var wipe = connection.CreateCommand();
            wipe.CommandText = "DELETE FROM origin_receive_state;";
            await wipe.ExecuteNonQueryAsync();
        }

        Assert.False((await store.GetKnownVectorAsync()).ContainsKey(PhoneDeviceId));
        Assert.IsType<RemoteStoreResult.AlreadyPersisted>(
            await store.StoreRemoteEventAsync(original, PhoneDeviceId));
        Assert.True((await store.GetKnownVectorAsync())[PhoneDeviceId].Contains(1));
    }

    [Fact]
    public async Task DeletedTerminalMarkerTombstonesStoredBody()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);

        var stored = RemoteEvent("kept body", 1);
        Assert.IsType<RemoteStoreResult.Stored>(await store.StoreRemoteEventAsync(stored, PhoneDeviceId));

        Assert.IsType<RemoteStoreResult.Stored>(await store.StoreRemoteTerminalAsync(
            new RemoteTerminalMarker(stored.EventId, PhoneDeviceId, 1, "deleted"),
            PhoneDeviceId,
            BaseTime.AddSeconds(1)));
        Assert.Null(await store.GetByIdAsync(stored.EventId));
        var tombstone = await store.GetByIdAsync(stored.EventId, includeDeleted: true);
        Assert.NotNull(tombstone);
        Assert.True(tombstone.IsDeleted);

        Assert.IsType<RemoteStoreResult.AlreadyPersisted>(await store.StoreRemoteTerminalAsync(
            new RemoteTerminalMarker(stored.EventId, PhoneDeviceId, 1, "deleted"),
            PhoneDeviceId,
            BaseTime.AddSeconds(2)));
    }

    [Fact]
    public async Task NonDeleteTerminalMarkerAdvancesCursorAndNeverReplacesStoredBody()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);

        var stored = RemoteEvent("kept body", 1);
        Assert.IsType<RemoteStoreResult.Stored>(await store.StoreRemoteEventAsync(stored, PhoneDeviceId));

        Assert.IsType<RemoteStoreResult.AlreadyPersisted>(await store.StoreRemoteTerminalAsync(
            new RemoteTerminalMarker(stored.EventId, PhoneDeviceId, 1, "local_only"),
            PhoneDeviceId,
            BaseTime.AddSeconds(1)));
        Assert.Equal("kept body", (await store.GetByIdAsync(stored.EventId))!.Text);

        var marker = new RemoteTerminalMarker(Guid.NewGuid(), PhoneDeviceId, 2, "local_only");
        var result = Assert.IsType<RemoteStoreResult.Stored>(
            await store.StoreRemoteTerminalAsync(marker, PhoneDeviceId, BaseTime.AddSeconds(2)));
        Assert.Equal(2, result.ReceiveState.ContiguousSeq);

        var syncable = await store.GetSyncableEventsAsync(PhoneDeviceId, new[] { new SequenceRange(2, 2) }, 10);
        Assert.Single(syncable);
        Assert.True(syncable[0].IsTerminal);
        Assert.Equal("local_only", syncable[0].TerminalReason);
        Assert.Null(syncable[0].Content);

        Assert.Null(await store.GetByIdAsync(marker.EventId));
    }

    [Fact]
    public async Task RemoteEventFansOutToOtherPeersButNotOriginOrSource()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        await store.UpsertDeviceAsync(Tablet(), BaseTime);

        Assert.IsType<RemoteStoreResult.Stored>(
            await store.StoreRemoteEventAsync(RemoteEvent("from phone", 1), PhoneDeviceId));

        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));
        var tabletBatch = await store.GetOutboxBatchAsync(TabletDeviceId, 10);
        Assert.Single(tabletBatch);
        Assert.Equal(PhoneDeviceId, tabletBatch[0].Entry.OriginDeviceId);
    }

    [Fact]
    public async Task AcksAdvancePeerCursorRemoveOutboxRowsAndSurviveReopen()
    {
        await using var database = new TemporaryDatabase();

        Guid keptEventId;
        await using (var store = database.CreateStore())
        {
            await store.UpsertDeviceAsync(Phone(), BaseTime);
            await store.StoreAsync(Content("one", BaseTime));
            await store.StoreAsync(Content("two", BaseTime.AddSeconds(3)));
            var third = await store.StoreAsync(Content("three", BaseTime.AddSeconds(6)));
            keptEventId = third.EventId;

            await store.ApplyPeerAckRangesAsync(
                PhoneDeviceId,
                new[] { new OriginSequenceRanges(LocalDeviceId, new[] { new SequenceRange(1, 2) }) },
                BaseTime.AddSeconds(7));

            var cursors = await store.GetPeerCursorsAsync(PhoneDeviceId);
            Assert.Equal(2, cursors[LocalDeviceId].ContiguousSeq);
        }

        // Unacked outbox entries survive a full process restart.
        await using var reopened = database.CreateStore();
        var remaining = await reopened.GetOutboxBatchAsync(PhoneDeviceId, 10);
        Assert.Single(remaining);
        Assert.Equal(keptEventId, remaining[0].Entry.EventId);
        Assert.Equal(3, remaining[0].Entry.OriginSeq);
    }

    [Fact]
    public async Task DeleteAfterAckRequeuesTombstoneOnOutbox()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        var stored = await store.StoreAsync(Content("synced-secret", BaseTime));
        await store.ApplyPeerAckRangesAsync(
            PhoneDeviceId,
            [new OriginSequenceRanges(LocalDeviceId, [new SequenceRange(stored.OriginSequence, stored.OriginSequence)])],
            BaseTime.AddSeconds(1));
        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        Assert.True(await store.DeleteAsync(stored.EventId, BaseTime.AddSeconds(2)));
        var batch = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        Assert.Single(batch);
        Assert.Equal(stored.EventId, batch[0].Entry.EventId);
        Assert.True(batch[0].Event.IsTerminal);
        Assert.Equal("deleted", batch[0].Event.TerminalReason);
        Assert.Null(batch[0].Event.Content);
    }

    [Fact]
    public async Task PersistedAckCoverageDoesNotDropTombstoneOutbox()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        var stored = await store.StoreAsync(Content("synced-secret", BaseTime));
        await store.ApplyPeerAckRangesAsync(
            PhoneDeviceId,
            [new OriginSequenceRanges(LocalDeviceId, [new SequenceRange(stored.OriginSequence, stored.OriginSequence)])],
            BaseTime.AddSeconds(1));
        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        Assert.True(await store.DeleteAsync(stored.EventId, BaseTime.AddSeconds(2)));
        Assert.Single(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        await store.ApplyPeerAckRangesAsync(
            PhoneDeviceId,
            [new OriginSequenceRanges(LocalDeviceId, [new SequenceRange(stored.OriginSequence, stored.OriginSequence)])],
            BaseTime.AddSeconds(3),
            dropTerminalOutbox: false);
        Assert.Single(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        await store.ApplyPeerAckRangesAsync(
            PhoneDeviceId,
            [new OriginSequenceRanges(LocalDeviceId, [new SequenceRange(stored.OriginSequence, stored.OriginSequence)])],
            BaseTime.AddSeconds(4));
        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));
    }

    [Fact]
    public async Task DeleteWhileAnnouncedRependsTombstoneOnOutbox()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        var stored = await store.StoreAsync(Content("in-flight-secret", BaseTime));
        var announced = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        await store.MarkOutboxAnnouncedAsync([announced[0].Entry.Id]);
        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        Assert.True(await store.DeleteAsync(stored.EventId, BaseTime.AddSeconds(1)));
        var batch = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        Assert.Single(batch);
        Assert.Equal(stored.EventId, batch[0].Entry.EventId);
        Assert.True(batch[0].Event.IsTerminal);
        Assert.Equal("deleted", batch[0].Event.TerminalReason);
    }

    [Fact]
    public async Task AnnouncedEntriesResetToPendingForANewSession()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        await store.StoreAsync(Content("first", BaseTime));

        var batch = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        await store.MarkOutboxAnnouncedAsync(new[] { batch[0].Entry.Id });
        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        await store.ResetOutboxToPendingAsync(PhoneDeviceId);
        var restored = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        Assert.Single(restored);
        Assert.Equal(1, restored[0].Entry.Attempts);
    }

    [Fact]
    public async Task RevokeBumpsTrustEpochClearsSecretAndDropsOutbox()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        var device = await store.UpsertDeviceAsync(Phone(), BaseTime);
        Assert.Equal(1, device.TrustEpoch);
        await store.StoreAsync(Content("queued", BaseTime));
        Assert.Single(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        Assert.True(await store.RevokeDeviceAsync(PhoneDeviceId, BaseTime.AddSeconds(1)));
        Assert.False(await store.RevokeDeviceAsync(PhoneDeviceId, BaseTime.AddSeconds(2)));

        var revoked = await store.GetDeviceAsync(PhoneDeviceId);
        Assert.NotNull(revoked);
        Assert.True(revoked.IsRevoked);
        Assert.Equal(2, revoked.TrustEpoch);
        Assert.Equal(string.Empty, revoked.PairSecretProtected);
        Assert.Empty(await store.GetOutboxBatchAsync(PhoneDeviceId, 10));

        // Re-pairing restores the device with a fresh epoch above every prior value.
        var repaired = await store.UpsertDeviceAsync(Phone(), BaseTime.AddSeconds(3));
        Assert.False(repaired.IsRevoked);
        Assert.Equal(3, repaired.TrustEpoch);
    }

    [Fact]
    public async Task SupersedeReplacedPeersRevokesSameNameGhostsAndDropsTheirOutbox()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();

        // The same phone paired earlier under a different device id (app data cleared since),
        // plus an unrelated tablet that must stay untouched.
        await store.UpsertDeviceAsync(
            new NewPairedDevice(GhostDeviceId, "Phone", "android", "cc".PadLeft(64, 'c'), "protected-secret-ghost"),
            BaseTime);
        await store.UpsertDeviceAsync(Tablet(), BaseTime);
        await store.StoreAsync(Content("queued before re-pair", BaseTime.AddSeconds(1)));
        Assert.Single(await store.GetOutboxBatchAsync(GhostDeviceId, 10));
        Assert.Single(await store.GetOutboxBatchAsync(TabletDeviceId, 10));

        // The phone re-pairs under a fresh id but the same display name and platform.
        await store.UpsertDeviceAsync(Phone(), BaseTime.AddSeconds(2));
        var superseded = await store.SupersedeReplacedPeersAsync(
            PhoneDeviceId, "Phone", "android", BaseTime.AddSeconds(3));

        Assert.Equal(new[] { GhostDeviceId }, superseded);
        var ghost = await store.GetDeviceAsync(GhostDeviceId);
        Assert.NotNull(ghost);
        Assert.True(ghost.IsRevoked);
        Assert.Equal(string.Empty, ghost.PairSecretProtected);
        Assert.Equal(2, ghost.TrustEpoch);
        Assert.Empty(await store.GetOutboxBatchAsync(GhostDeviceId, 10));

        // The fresh pairing and the differently named tablet keep their state and backlog.
        Assert.False((await store.GetDeviceAsync(PhoneDeviceId))!.IsRevoked);
        Assert.False((await store.GetDeviceAsync(TabletDeviceId))!.IsRevoked);
        Assert.Single(await store.GetOutboxBatchAsync(TabletDeviceId, 10));

        // Idempotent: with the ghost already revoked there is nothing left to supersede.
        Assert.Empty(await store.SupersedeReplacedPeersAsync(
            PhoneDeviceId, "Phone", "android", BaseTime.AddSeconds(4)));
    }

    [Fact]
    public async Task OutboxDepthByPeerReportsEachPeersBacklog()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        await store.UpsertDeviceAsync(Tablet(), BaseTime);
        Assert.Empty(await store.GetOutboxDepthByPeerAsync());

        await store.StoreAsync(Content("first", BaseTime.AddSeconds(1)));
        await store.StoreAsync(Content("second", BaseTime.AddSeconds(2)));

        var depths = await store.GetOutboxDepthByPeerAsync();
        Assert.Equal(2, depths[PhoneDeviceId]);
        Assert.Equal(2, depths[TabletDeviceId]);

        // Acked rows leave the outbox, and peers with no backlog are absent from the map.
        await store.ApplyPeerAckRangesAsync(
            PhoneDeviceId,
            new[] { new OriginSequenceRanges(LocalDeviceId, new[] { new SequenceRange(1, 2) }) },
            BaseTime.AddSeconds(3));
        depths = await store.GetOutboxDepthByPeerAsync();
        Assert.False(depths.ContainsKey(PhoneDeviceId));
        Assert.Equal(2, depths[TabletDeviceId]);
    }

    [Fact]
    public async Task RemoteStoreFaultBeforeCommitLeavesNoPartialState()
    {
        await using var database = new TemporaryDatabase();
        var injector = new ThrowOnceFaultInjector(StorageFaultPoint.BeforeCommit);
        await using var store = database.CreateStore(injector);
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        await store.UpsertDeviceAsync(Tablet(), BaseTime);

        await Assert.ThrowsAsync<InjectedStorageException>(
            () => store.StoreRemoteEventAsync(RemoteEvent("must roll back", 1), PhoneDeviceId).AsTask());

        Assert.Empty(await store.GetSyncableEventsAsync(PhoneDeviceId, new[] { new SequenceRange(1, 1) }, 10));
        Assert.False((await store.GetKnownVectorAsync()).ContainsKey(PhoneDeviceId));
        Assert.Empty(await store.GetOutboxBatchAsync(TabletDeviceId, 10));

        // The same event succeeds after the fault clears.
        Assert.IsType<RemoteStoreResult.Stored>(await store.StoreRemoteEventAsync(RemoteEvent("must roll back", 1), PhoneDeviceId));
    }

    [Fact]
    public async Task SyncableQueriesRespectRangesCapsAndHashLookupIgnoresDeletedRows()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);
        for (long seq = 1; seq <= 5; seq++)
        {
            await store.StoreRemoteEventAsync(RemoteEvent($"clip {seq}", seq), PhoneDeviceId);
        }

        var ranges = new[] { new SequenceRange(1, 2), new SequenceRange(4, 5) };
        var capped = await store.GetSyncableEventsAsync(PhoneDeviceId, ranges, 3);
        Assert.Equal(new long[] { 1, 2, 4 }, capped.Select(item => item.OriginSeq));

        var byIds = await store.GetSyncableEventsByIdsAsync(new[] { capped[0].EventId, capped[2].EventId });
        Assert.Equal(2, byIds.Count);

        Assert.Equal("clip 3", await store.FindLiveContentByHashAsync(Hash("clip 3")));
        var thirdId = (await store.GetSyncableEventsAsync(PhoneDeviceId, new[] { new SequenceRange(3, 3) }, 1))[0].EventId;
        await store.DeleteAsync(thirdId, BaseTime.AddDays(1));
        Assert.Null(await store.FindLiveContentByHashAsync(Hash("clip 3")));
    }

    [Fact]
    public async Task OutboxStatusTracksQueueDepthAndLastAck()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();

        var empty = await store.GetOutboxStatusAsync();
        Assert.Equal(0, empty.PendingCount);
        Assert.Null(empty.LastPeerAckAt);

        await store.UpsertDeviceAsync(Phone(), BaseTime);
        await store.StoreAsync(Content("one", BaseTime));
        await store.StoreAsync(Content("two", BaseTime.AddSeconds(1)));

        var queued = await store.GetOutboxStatusAsync();
        Assert.Equal(2, queued.PendingCount);
        Assert.Null(queued.LastPeerAckAt);

        // Announced-but-unacked rows still count: no peer has confirmed them yet.
        var batch = await store.GetOutboxBatchAsync(PhoneDeviceId, 10);
        await store.MarkOutboxAnnouncedAsync(new[] { batch[0].Entry.Id });
        Assert.Equal(2, (await store.GetOutboxStatusAsync()).PendingCount);

        var ackTime = BaseTime.AddSeconds(5);
        await store.ApplyPeerAckRangesAsync(
            PhoneDeviceId,
            new[] { new OriginSequenceRanges(LocalDeviceId, new[] { new SequenceRange(1, 1) }) },
            ackTime);

        var acked = await store.GetOutboxStatusAsync();
        Assert.Equal(1, acked.PendingCount);
        Assert.NotNull(acked.LastPeerAckAt);
        Assert.Equal(ackTime.ToUnixTimeMilliseconds(), acked.LastPeerAckAt.Value.ToUnixTimeMilliseconds());
    }

    [Fact]
    public async Task RenameDeviceChangesOnlyTheDisplayName()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);

        Assert.True(await store.RenameDeviceAsync(PhoneDeviceId, "  Kitchen Phone  "));
        Assert.False(await store.RenameDeviceAsync(TabletDeviceId, "Nobody"));

        var renamed = await store.GetDeviceAsync(PhoneDeviceId);
        Assert.NotNull(renamed);
        Assert.Equal("Kitchen Phone", renamed.DisplayName);
        Assert.Equal(1, renamed.TrustEpoch);
        Assert.Equal("protected-secret-phone", renamed.PairSecretProtected);
    }

    [Fact]
    public async Task UpsertWithExplicitEpochPinsTheAgreedValue()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();

        // The scanner side stores exactly the epoch from the confirm response.
        var first = await store.UpsertDeviceWithEpochAsync(Phone(), trustEpoch: 4, BaseTime);
        Assert.Equal(4, first.TrustEpoch);

        // Re-pairing overwrites with the newly agreed epoch instead of incrementing blindly.
        await store.RevokeDeviceAsync(PhoneDeviceId, BaseTime.AddSeconds(1));
        var repaired = await store.UpsertDeviceWithEpochAsync(
            Phone() with { PairSecretProtected = "protected-secret-new" },
            trustEpoch: 6,
            BaseTime.AddSeconds(2));
        Assert.Equal(6, repaired.TrustEpoch);
        Assert.False(repaired.IsRevoked);
        Assert.Equal("protected-secret-new", repaired.PairSecretProtected);
    }

    [Fact]
    public async Task DeviceAccentOverridePersistsSurvivesRepairAndClears()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.UpsertDeviceAsync(Phone(), BaseTime);

        // New devices follow pairing order: no override stored.
        Assert.Null((await store.GetDeviceAsync(PhoneDeviceId))!.AccentOverride);

        // 设备色手动改 (P1#14): the pin persists and out-of-range slots are refused.
        Assert.True(await store.SetDeviceAccentAsync(PhoneDeviceId, 4));
        Assert.Equal(4, (await store.GetDeviceAsync(PhoneDeviceId))!.AccentOverride);
        Assert.False(await store.SetDeviceAccentAsync(TabletDeviceId, 2));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(
            () => store.SetDeviceAccentAsync(PhoneDeviceId, 0).AsTask());
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(
            () => store.SetDeviceAccentAsync(PhoneDeviceId, 6).AsTask());

        // The colour belongs to the device identity: revoke + re-pair upsert keeps the pin.
        await store.RevokeDeviceAsync(PhoneDeviceId, BaseTime.AddSeconds(1));
        await store.UpsertDeviceAsync(
            Phone() with { PairSecretProtected = "protected-secret-new" },
            BaseTime.AddSeconds(2));
        Assert.Equal(4, (await store.GetDeviceAsync(PhoneDeviceId))!.AccentOverride);

        // Null clears the pin, back to 跟随配对顺位.
        Assert.True(await store.SetDeviceAccentAsync(PhoneDeviceId, null));
        Assert.Null((await store.GetDeviceAsync(PhoneDeviceId))!.AccentOverride);
    }

    private static NewPairedDevice Phone() =>
        new(PhoneDeviceId, "Phone", "android", "aa".PadLeft(64, 'a'), "protected-secret-phone");

    private static NewPairedDevice Tablet() =>
        new(TabletDeviceId, "Tablet", "android", "bb".PadLeft(64, 'b'), "protected-secret-tablet");

    private static RemoteClipEvent RemoteEvent(string content, long seq) =>
        new(Guid.NewGuid(), PhoneDeviceId, seq, content, Hash(content), "app", BaseTime, null);

    private static string Hash(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private static AcceptedClipboardContent Content(string text, DateTimeOffset capturedAt)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        return new AcceptedClipboardContent(text, Hash(text), bytes.Length, "notepad", capturedAt);
    }

    private static async Task CreateVersionOneDatabaseAsync(string path)
    {
        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate
        }.ToString();

        await using var connection = new SqliteConnection(connectionString);
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA journal_mode = WAL;

            CREATE TABLE clips (
                event_id TEXT PRIMARY KEY,
                origin_device_id TEXT NOT NULL,
                origin_seq INTEGER NOT NULL CHECK (origin_seq >= 1),
                kind TEXT NOT NULL CHECK (kind = 'text'),
                content TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                source_app TEXT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                deleted_at INTEGER,
                UNIQUE(origin_device_id, origin_seq)
            );

            CREATE INDEX clips_visible_history_idx
                ON clips(deleted_at, created_at DESC, origin_seq DESC);

            CREATE TABLE local_sequences (
                device_id TEXT PRIMARY KEY,
                next_seq INTEGER NOT NULL DEFAULT 1 CHECK (next_seq >= 1)
            );

            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            INSERT INTO clips VALUES (
                '99999999-9999-4999-8999-999999999999',
                '11111111-1111-4111-8111-111111111111',
                1, 'text', 'legacy content', 'unused-hash', 'legacy-app', 1700000000000, NULL, NULL);
            INSERT INTO local_sequences VALUES ('11111111-1111-4111-8111-111111111111', 2);

            PRAGMA user_version = 1;
            """;
        await command.ExecuteNonQueryAsync();
        SqliteConnection.ClearAllPools();
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

        public string Path => System.IO.Path.Combine(directory, "sync.db");

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
