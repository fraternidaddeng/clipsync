package com.clipsync.android.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.sync.OriginSequenceRanges
import com.clipsync.android.sync.RoomSyncRepository
import com.clipsync.android.sync.SequenceRange
import com.clipsync.android.sync.SyncableClipEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RoomSyncRepository.applyPeerAckRanges] is the [com.clipsync.android.sync.SyncEngine]
 * ACK_RANGES path against the real Room store. Re-applying the same coverage must not move
 * peer cursors, resurrect acked rows, or drop the remaining outbox row.
 */
@RunWith(RobolectricTestRunner::class)
class AckIdempotencyTest {
    private lateinit var database: ClipSyncDatabase
    private lateinit var store: ClipSyncRepository
    private lateinit var repository: RoomSyncRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ClipSyncRepository(database, LOCAL_DEVICE)
        repository = RoomSyncRepository(store = store, fanOutPeerIds = { listOf(PEER) })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `applying the same ack ranges twice is a no-op for outbox and peer cursors`() = runBlocking {
        val first = storeLocal("ack-1", NOW_MS, captureIndex = 1)
        val second = storeLocal("ack-2", NOW_MS + STEP_MS, captureIndex = 2)
        val third = storeLocal("ack-3", NOW_MS + STEP_MS * 2, captureIndex = 3)
        assertEquals("three local captures must fan out three outbox rows", 3, store.pendingOutboxCount(PEER))

        // Engine drain marks a batch announced before the peer's ACK_RANGES. Leave seq 3
        // pending so the unacked row is still visible after the first ack.
        val batch = repository.getOutboxBatch(PEER, 10)
        assertEquals(3, batch.size)
        val announced = batch.filter { it.event.eventId == first.eventId || it.event.eventId == second.eventId }
        repository.markOutboxAnnounced(announced.map { it.entryId })
        assertEquals(
            "unacked third capture must remain pending after announce",
            1,
            store.pendingOutboxCount(PEER),
        )

        val firstAck = listOf(
            OriginSequenceRanges(
                LOCAL_DEVICE,
                listOf(SequenceRange(first.originSeq, second.originSeq)),
            ),
        )
        repository.applyPeerAckRanges(PEER, firstAck, NOW_MS + STEP_MS * 3)

        val outboxAfterFirst = repository.getOutboxBatch(PEER, 10)
        val cursorAfterFirst = store.peerCursors(PEER)[LOCAL_DEVICE]
        assertEquals("first ack of seq 1-2 must leave exactly one outbox row", 1, outboxAfterFirst.size)
        assertEquals(
            "remaining outbox row must be the unacked third capture",
            third.eventId,
            outboxAfterFirst.single().event.eventId,
        )
        assertNotNull("peer cursor for the local origin must exist after the first ack", cursorAfterFirst)
        assertEquals(
            "first ack must advance the peer cursor through seq 2",
            2L,
            cursorAfterFirst!!.contiguousSeq,
        )
        assertTrue(
            "first ack must leave no holes above the contiguous cursor",
            cursorAfterFirst.receivedRanges.isEmpty(),
        )

        // Replay the identical ack: nothing may move.
        repository.applyPeerAckRanges(PEER, firstAck, NOW_MS + STEP_MS * 4)

        val outboxAfterReplay = repository.getOutboxBatch(PEER, 10)
        val cursorAfterReplay = store.peerCursors(PEER)[LOCAL_DEVICE]
        assertEquals(
            "second identical ack must not change the outbox",
            outboxAfterFirst.map { it.event.eventId },
            outboxAfterReplay.map { it.event.eventId },
        )
        assertNotNull("peer cursor must still exist after the replayed ack", cursorAfterReplay)
        assertEquals(
            "second identical ack must not change the peer cursor",
            cursorAfterFirst.contiguousSeq,
            cursorAfterReplay!!.contiguousSeq,
        )
        assertEquals(
            "replayed ack must not invent received-range holes",
            cursorAfterFirst.receivedRanges,
            cursorAfterReplay.receivedRanges,
        )

        // Acked rows were deleted, not parked: a reset-to-pending resurrects nothing.
        repository.resetOutboxToPending(PEER)
        assertEquals(
            "reset after ack must only re-expose the unacked row",
            listOf(third.eventId),
            repository.getOutboxBatch(PEER, 10).map { it.event.eventId },
        )

        repository.applyPeerAckRanges(
            PEER,
            listOf(
                OriginSequenceRanges(
                    LOCAL_DEVICE,
                    listOf(SequenceRange(third.originSeq, third.originSeq)),
                ),
            ),
            NOW_MS + STEP_MS * 5,
        )
        assertTrue(
            "acking the remaining seq must drain the outbox",
            repository.getOutboxBatch(PEER, 10).isEmpty(),
        )
        assertEquals("outbox pending count must be zero", 0, store.pendingOutboxCount(PEER))
        assertEquals(
            "final ack must advance the peer cursor through seq 3",
            3L,
            store.peerCursors(PEER).getValue(LOCAL_DEVICE).contiguousSeq,
        )
    }

    private suspend fun storeLocal(
        text: String,
        nowMs: Long,
        captureIndex: Int,
    ): SyncableClipEvent {
        val captured = repository.recordLocalClip(text, sourceApp = null, nowMs = nowMs)
        assertNotNull("local capture must store at index $captureIndex", captured)
        assertEquals(
            "local capture must allocate contiguous sequences",
            captureIndex.toLong(),
            captured!!.originSeq,
        )
        return captured
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val STEP_MS = 3_000L
        const val LOCAL_DEVICE = "22222222-2222-4222-8222-222222222222"
        const val PEER = "11111111-1111-4111-8111-111111111111"
    }
}
