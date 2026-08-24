package com.clipsync.android.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.sync.SequenceRange
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipSyncRepositoryTest {
    private lateinit var database: ClipSyncDatabase
    private lateinit var repository: ClipSyncRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ClipSyncRepository(database, LOCAL_DEVICE)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- Local events ----

    @Test
    fun localEventsAllocateMonotonicSequencesAndFanOutToPeers() = runBlocking {
        val first = repository.storeLocalEvent(draft("first"), listOf(PEER))
        val second = repository.storeLocalEvent(draft("second"), listOf(PEER))

        assertEquals(1, first.originSeq)
        assertEquals(2, second.originSeq)
        assertEquals(LOCAL_DEVICE, first.originDeviceId)
        assertEquals(2, repository.pendingOutboxCount(PEER))

        val vector = repository.knownVector()
        assertEquals(2, vector.getValue(LOCAL_DEVICE).contiguousSeq)
    }

    @Test
    fun localEventNeverEnqueuesToItsOwnOrigin() = runBlocking {
        repository.storeLocalEvent(draft("text"), listOf(LOCAL_DEVICE, PEER, PEER))
        assertEquals(0, repository.pendingOutboxCount(LOCAL_DEVICE))
        assertEquals(1, repository.pendingOutboxCount(PEER))
    }

    // ---- Remote events and idempotency ----

    @Test
    fun remoteEventStoresAndAdvancesReceiveState() = runBlocking {
        val result = repository.storeRemoteEvent(remoteEvent(seq = 1), sourcePeerId = PEER)

        val stored = result as RemoteStoreResult.Stored
        assertEquals(1, stored.receiveState.contiguousSeq)
        assertEquals(1, repository.knownVector().getValue(REMOTE_ORIGIN).contiguousSeq)
        assertNotNull(repository.getById(eventId(1)))
    }

    @Test
    fun identicalRemoteRetryIsAlreadyPersisted() = runBlocking {
        val event = remoteEvent(seq = 1)
        repository.storeRemoteEvent(event, sourcePeerId = PEER)

        val retry = repository.storeRemoteEvent(event, sourcePeerId = PEER)
        assertTrue(retry is RemoteStoreResult.AlreadyPersisted)
        assertEquals(1, database.clipEvents().countVisible())
    }

    @Test
    fun sameSequenceWithDifferentEventIdIsIdentityConflict() = runBlocking {
        repository.storeRemoteEvent(remoteEvent(seq = 1), sourcePeerId = PEER)

        val conflicting = remoteEvent(seq = 1).copy(eventId = eventId(99))
        val result = repository.storeRemoteEvent(conflicting, sourcePeerId = PEER)
        assertTrue(result is RemoteStoreResult.IdentityConflict)
    }

    @Test
    fun sameEventIdWithDifferentSequenceIsIdentityConflict() = runBlocking {
        repository.storeRemoteEvent(remoteEvent(seq = 1), sourcePeerId = PEER)

        val conflicting = remoteEvent(seq = 2).copy(eventId = eventId(1))
        val result = repository.storeRemoteEvent(conflicting, sourcePeerId = PEER)
        assertTrue(result is RemoteStoreResult.IdentityConflict)
    }

    @Test
    fun sameIdentityWithDifferentContentHashIsIdentityConflict() = runBlocking {
        repository.storeRemoteEvent(remoteEvent(seq = 1), sourcePeerId = PEER)

        val conflicting = remoteEvent(seq = 1).copy(contentHash = Sha256ContentHasher.hash("tampered"))
        val result = repository.storeRemoteEvent(conflicting, sourcePeerId = PEER)
        assertTrue(result is RemoteStoreResult.IdentityConflict)
    }

    @Test
    fun outOfOrderSequenceKeepsContiguousCursorBehindGap() = runBlocking {
        repository.storeRemoteEvent(remoteEvent(seq = 1), sourcePeerId = PEER)
        repository.storeRemoteEvent(remoteEvent(seq = 2), sourcePeerId = PEER)
        val gapped = repository.storeRemoteEvent(remoteEvent(seq = 4), sourcePeerId = PEER)

        val afterGap = (gapped as RemoteStoreResult.Stored).receiveState
        assertEquals(2, afterGap.contiguousSeq)
        assertEquals(listOf(SequenceRange(4, 4)), afterGap.receivedRanges)

        val filled = repository.storeRemoteEvent(remoteEvent(seq = 3), sourcePeerId = PEER)
        val afterFill = (filled as RemoteStoreResult.Stored).receiveState
        assertEquals(4, afterFill.contiguousSeq)
        assertTrue(afterFill.receivedRanges.isEmpty())
    }

    @Test
    fun remoteTerminalMarkerAdvancesCursorWithoutContent() = runBlocking {
        repository.storeRemoteEvent(remoteEvent(seq = 1), sourcePeerId = PEER)
        val marker = RemoteTerminalMarker(eventId(2), REMOTE_ORIGIN, 2, TerminalReasons.DELETED)

        val result = repository.storeRemoteTerminal(marker, sourcePeerId = PEER, receivedAtMs = NOW)

        assertEquals(2, (result as RemoteStoreResult.Stored).receiveState.contiguousSeq)
        assertNull(repository.getById(eventId(2)))

        val retry = repository.storeRemoteTerminal(marker, sourcePeerId = PEER, receivedAtMs = NOW)
        assertTrue(retry is RemoteStoreResult.AlreadyPersisted)
    }

    // ---- History and search ----

    @Test
    fun searchFindsSubstringsAndExcludesDeleted() = runBlocking {
        repository.storeLocalEvent(draft("alpha token"), emptyList())
        val deleted = repository.storeLocalEvent(draft("beta token"), emptyList())
        repository.storeLocalEvent(draft("gamma"), emptyList())
        repository.deleteEvent(deleted.eventId, NOW)

        val all = repository.searchHistory()
        assertEquals(listOf("gamma", "alpha token"), all.map { it.content })

        val matched = repository.searchHistory(HistoryQuery(searchText = "token"))
        assertEquals(listOf("alpha token"), matched.map { it.content })
    }

    @Test
    fun searchEscapesLikeWildcards() = runBlocking {
        repository.storeLocalEvent(draft("100% done"), emptyList())
        repository.storeLocalEvent(draft("100x done"), emptyList())

        val matched = repository.searchHistory(HistoryQuery(searchText = "100%"))
        assertEquals(listOf("100% done"), matched.map { it.content })
    }

    @Test
    fun historyOrdersNewestFirstAndPaginates() = runBlocking {
        for (index in 1..5) {
            repository.storeLocalEvent(draft("clip $index", capturedAtMs = NOW + index), emptyList())
        }

        val page = repository.searchHistory(HistoryQuery(limit = 2, offset = 1))
        assertEquals(listOf("clip 4", "clip 3"), page.map { it.content })
    }

    // ---- Delete markers and applied state ----

    @Test
    fun deleteErasesContentButKeepsTerminalRowForSync() = runBlocking {
        val stored = repository.storeLocalEvent(draft("secret"), listOf(PEER))
        assertTrue(repository.deleteEvent(stored.eventId, NOW))
        assertFalse(repository.deleteEvent(stored.eventId, NOW))

        assertNull(repository.getById(stored.eventId))
        val tombstone = repository.getById(stored.eventId, includeDeleted = true)!!
        assertEquals("", tombstone.content)
        assertTrue(tombstone.isDeleted)

        // The still-queued outbox row now projects a terminal announce, exactly like Windows.
        val batch = repository.outboxBatch(PEER, limit = 10)
        assertEquals(1, batch.size)
        assertTrue(batch[0].event.isTerminal)
        assertEquals(TerminalReasons.DELETED, batch[0].event.terminalReason)
        assertNull(batch[0].event.content)
    }

    @Test
    fun clearHistorySoftDeletesEverything() = runBlocking {
        repository.storeLocalEvent(draft("one"), emptyList())
        repository.storeLocalEvent(draft("two"), emptyList())

        assertEquals(2, repository.clearHistory(NOW))
        assertTrue(repository.searchHistory().isEmpty())
        assertEquals(0, database.clipEvents().countVisible())
    }

    @Test
    fun markAppliedRecordsApplyStateOnlyForLiveRows() = runBlocking {
        val result = repository.storeRemoteEvent(remoteEvent(seq = 1), sourcePeerId = PEER)
        assertTrue(result is RemoteStoreResult.Stored)

        assertTrue(repository.markApplied(eventId(1), NOW + 5))
        assertEquals(NOW + 5, repository.getById(eventId(1))!!.appliedAtMs)

        repository.deleteEvent(eventId(1), NOW + 6)
        assertFalse(repository.markApplied(eventId(1), NOW + 7))
    }

    @Test
    fun cleanupExpiresRowsBeyondAgeOrCount() = runBlocking {
        val old = repository.storeLocalEvent(draft("old", capturedAtMs = NOW - 100), emptyList())
        for (index in 1..3) {
            repository.storeLocalEvent(draft("recent $index", capturedAtMs = NOW + index), emptyList())
        }

        val removed = repository.cleanup(RetentionPolicy(maximumEntries = 2, maximumAgeMs = 50), NOW)

        assertEquals(2, removed)
        assertNull(repository.getById(old.eventId))
        assertEquals(listOf("recent 3", "recent 2"), repository.searchHistory().map { it.content })
        val expired = repository.getById(old.eventId, includeDeleted = true)!!
        assertTrue(expired.isDeleted)
    }

    // ---- Outbox lifecycle ----

    @Test
    fun outboxBatchAnnounceAndResetLifecycle() = runBlocking {
        repository.storeLocalEvent(draft("one"), listOf(PEER))
        repository.storeLocalEvent(draft("two"), listOf(PEER))

        val batch = repository.outboxBatch(PEER, limit = 10)
        assertEquals(listOf(1L, 2L), batch.map { it.event.originSeq })
        assertEquals("one", batch[0].event.content)

        repository.markOutboxAnnounced(batch.map { it.outboxId })
        assertEquals(0, repository.pendingOutboxCount(PEER))
        assertTrue(repository.outboxBatch(PEER, limit = 10).isEmpty())

        // A new session returns announced-but-unacked entries to pending.
        repository.resetOutboxToPending(PEER)
        assertEquals(2, repository.pendingOutboxCount(PEER))
        assertEquals(1, repository.outboxBatch(PEER, limit = 10)[0].attempts)
    }

    @Test
    fun peerAcksAdvanceCursorAndPruneOutbox() = runBlocking {
        for (index in 1..3) {
            repository.storeLocalEvent(draft("clip $index"), listOf(PEER))
        }

        repository.applyPeerAckRanges(
            PEER,
            listOf(OriginAckRanges(LOCAL_DEVICE, listOf(SequenceRange(1, 2)))),
            NOW,
        )

        assertEquals(1, repository.pendingOutboxCount(PEER))
        assertEquals(2, repository.peerCursors(PEER).getValue(LOCAL_DEVICE).contiguousSeq)

        repository.applyPeerAckRanges(
            PEER,
            listOf(OriginAckRanges(LOCAL_DEVICE, listOf(SequenceRange(3, 3)))),
            NOW,
        )
        assertEquals(0, repository.pendingOutboxCount(PEER))
        assertEquals(0, repository.totalPendingOutboxCount())
        assertEquals(3, repository.peerCursors(PEER).getValue(LOCAL_DEVICE).contiguousSeq)
    }

    @Test
    fun peerCursorKeepsGapSemanticsForOutOfOrderAcks() = runBlocking {
        repository.applyPeerAckRanges(
            PEER,
            listOf(OriginAckRanges(LOCAL_DEVICE, listOf(SequenceRange(4, 5)))),
            NOW,
        )

        val gapped = repository.peerCursors(PEER).getValue(LOCAL_DEVICE)
        assertEquals(0, gapped.contiguousSeq)
        assertEquals(listOf(SequenceRange(4, 5)), gapped.receivedRanges)

        repository.applyPeerAckRanges(
            PEER,
            listOf(OriginAckRanges(LOCAL_DEVICE, listOf(SequenceRange(1, 3)))),
            NOW,
        )
        val merged = repository.peerCursors(PEER).getValue(LOCAL_DEVICE)
        assertEquals(5, merged.contiguousSeq)
        assertTrue(merged.receivedRanges.isEmpty())
    }

    @Test
    fun forgetPeerDropsQueueAndCursorsButKeepsHistory() = runBlocking {
        repository.storeLocalEvent(draft("keep me"), listOf(PEER))
        repository.applyPeerAckRanges(
            PEER,
            listOf(OriginAckRanges(LOCAL_DEVICE, listOf(SequenceRange(1, 1)))),
            NOW,
        )

        repository.forgetPeer(PEER)

        assertEquals(0, repository.pendingOutboxCount(PEER))
        assertTrue(repository.peerCursors(PEER).isEmpty())
        assertEquals(1, repository.searchHistory().size)
    }

    // ---- Sync projection ----

    @Test
    fun syncableEventsReturnRequestedRangesInOrder() = runBlocking {
        for (index in 1..5) {
            repository.storeLocalEvent(draft("clip $index"), emptyList())
        }

        val events = repository.getSyncableEvents(
            LOCAL_DEVICE,
            listOf(SequenceRange(1, 2), SequenceRange(4, 5)),
            maximumEvents = 3,
        )
        assertEquals(listOf(1L, 2L, 4L), events.map { it.originSeq })
    }

    @Test
    fun syncableEventsByIdsAndHashLookup() = runBlocking {
        val stored = repository.storeLocalEvent(draft("findable"), emptyList())

        val byId = repository.getSyncableEventsByIds(listOf(stored.eventId))
        assertEquals(listOf("findable"), byId.map { it.content })
        assertTrue(repository.getSyncableEventsByIds(emptyList()).isEmpty())

        val hash = Sha256ContentHasher.hash("findable")
        assertEquals("findable", repository.findLiveContentByHash(hash))

        repository.deleteEvent(stored.eventId, NOW)
        assertNull(repository.findLiveContentByHash(hash))
    }

    private fun draft(text: String, capturedAtMs: Long = NOW) = LocalClipDraft(
        content = text,
        contentHash = Sha256ContentHasher.hash(text),
        sourceApp = "com.example.app",
        capturedAtMs = capturedAtMs,
    )

    private fun remoteEvent(seq: Long) = RemoteClipEvent(
        eventId = eventId(seq),
        originDeviceId = REMOTE_ORIGIN,
        originSeq = seq,
        content = "remote $seq",
        contentHash = Sha256ContentHasher.hash("remote $seq"),
        sourceApp = null,
        createdAtMs = NOW,
        expiresAtMs = null,
    )

    private fun eventId(seq: Long): String =
        "00000000-0000-4000-8000-%012d".format(seq)

    private companion object {
        const val LOCAL_DEVICE = "android-device-1"
        const val REMOTE_ORIGIN = "windows-device-1"
        const val PEER = "windows-device-1"
        const val NOW = 1_700_000_000_000L
    }
}
