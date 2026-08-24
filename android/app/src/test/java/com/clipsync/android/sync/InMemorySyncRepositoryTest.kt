package com.clipsync.android.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LOCAL = "22222222-2222-4222-8222-222222222222"
private const val PEER = "11111111-1111-4111-8111-111111111111"

class InMemorySyncRepositoryTest {
    private fun remote(seq: Long, eventId: String, content: String = "clip $seq") = RemoteClipEvent(
        eventId = eventId,
        originDeviceId = PEER,
        originSeq = seq,
        content = content,
        contentHash = "hash-$seq",
        sourceApp = null,
        createdAtMs = 1_000,
        expiresAtMs = null,
    )

    @Test
    fun `storing remote events advances the receive state and is idempotent`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        val event = remote(1, "33333333-3333-4333-8333-333333333333")
        assertEquals(RemoteStoreResult.Stored, repository.storeRemoteEvent(event, PEER))
        assertEquals(RemoteStoreResult.Duplicate, repository.storeRemoteEvent(event, PEER))
        assertEquals(1, repository.knownVector().getValue(PEER).contiguousSeq)
    }

    @Test
    fun `the same idempotency key with a different identity is a conflict`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        repository.storeRemoteEvent(remote(1, "33333333-3333-4333-8333-333333333333"), PEER)
        val conflicting = remote(1, "44444444-4444-4444-8444-444444444444")
        assertTrue(repository.storeRemoteEvent(conflicting, PEER) is RemoteStoreResult.IdentityConflict)
    }

    @Test
    fun `one event id cannot map to two origin sequences`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        repository.storeRemoteEvent(remote(1, "33333333-3333-4333-8333-333333333333"), PEER)
        assertTrue(
            repository.storeRemoteEvent(
                remote(2, "33333333-3333-4333-8333-333333333333"),
                PEER,
            ) is RemoteStoreResult.IdentityConflict,
        )
    }

    @Test
    fun `terminal markers advance the cursor without content`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        val marker = RemoteTerminalMarker("55555555-5555-4555-8555-555555555555", PEER, 1, "local_only")
        assertEquals(RemoteStoreResult.Stored, repository.storeRemoteTerminal(marker, PEER))
        assertEquals(1, repository.knownVector().getValue(PEER).contiguousSeq)
        assertNull(repository.findLiveContentByHash("anything"))
    }

    @Test
    fun `outbox lifecycle - record, announce, reset, ack`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        val event = repository.recordLocalClip("phone clip", sourceApp = null, nowMs = 5_000)!!
        assertEquals(1, event.originSeq)
        assertEquals(1, repository.knownVector().getValue(LOCAL).contiguousSeq)

        val batch = repository.getOutboxBatch(PEER, 10)
        assertEquals(listOf(event.eventId), batch.map { it.event.eventId })

        repository.markOutboxAnnounced(batch.map { it.entryId })
        assertTrue(repository.getOutboxBatch(PEER, 10).isEmpty())

        // A reconnect re-announces anything not yet acknowledged.
        repository.resetOutboxToPending(PEER)
        assertEquals(1, repository.getOutboxBatch(PEER, 10).size)

        repository.applyPeerAckRanges(
            PEER,
            listOf(OriginSequenceRanges(LOCAL, listOf(SequenceRange(1, 1)))),
            nowMs = 6_000,
        )
        repository.resetOutboxToPending(PEER)
        assertTrue(repository.getOutboxBatch(PEER, 10).isEmpty())
    }

    @Test
    fun `empty and oversized local clips are not events`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        assertNull(repository.recordLocalClip("", sourceApp = null, nowMs = 1))
        val oversized = "a".repeat(SyncLimits.MAX_CONTENT_UTF8_BYTES + 1)
        assertNull(repository.recordLocalClip(oversized, sourceApp = null, nowMs = 1))
    }

    @Test
    fun `live content is found by hash for replay without refetch`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        val event = repository.recordLocalClip("shared text", sourceApp = null, nowMs = 1)!!
        assertEquals("shared text", repository.findLiveContentByHash(event.contentHash!!))
    }

    @Test
    fun `syncable events are served by range and by id`() = runTest {
        val repository = InMemorySyncRepository(LOCAL)
        val first = repository.recordLocalClip("one", sourceApp = null, nowMs = 1)!!
        val second = repository.recordLocalClip("two", sourceApp = null, nowMs = 2)!!

        val ranged = repository.getSyncableEvents(LOCAL, listOf(SequenceRange(1, 2)), 10)
        assertEquals(listOf(first.eventId, second.eventId), ranged.map { it.eventId })

        val byIds = repository.getSyncableEventsByIds(listOf(second.eventId, "unknown"))
        assertEquals(listOf(second.eventId), byIds.map { it.eventId })
    }
}
