package com.clipsync.android.e2e

import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.OriginSequenceRanges
import com.clipsync.android.storage.SequenceRange
import com.clipsync.android.storage.TEST_LOCAL_DEVICE_ID
import com.clipsync.android.storage.TEST_PEER_DEVICE_ID
import com.clipsync.android.storage.createTestClipRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [com.clipsync.android.storage.ClipRepository.ackRanges] is the SyncSessionEngine ACK path.
 * Re-applying the same coverage must not move cursors or drop remaining outbox rows.
 */
class AckIdempotencyTest {
    @Test
    fun `applying the same ack ranges twice is a no-op for outbox and peer cursors`() = runTest {
        val repo = createTestClipRepository()
        repo.initialize()

        val first = storeLocal(repo, "ack-1", NOW_MS, captureIndex = 1)
        val second = storeLocal(repo, "ack-2", NOW_MS + STEP_MS, captureIndex = 2)
        val third = storeLocal(repo, "ack-3", NOW_MS + STEP_MS * 2, captureIndex = 3)

        val pending = repo.outboxPending(TEST_PEER_DEVICE_ID)
        assertEquals("three local captures must fan out three outbox rows", 3, pending.size)
        // Engine marks a batch announced before ACK_RANGES. Leave seq 3 pending so
        // outboxPending still exposes the unacked row after the first ack.
        val announced = pending.filter { it.eventId == first.eventId || it.eventId == second.eventId }
        repo.markAnnounced(announced.map { it.id })
        assertEquals(
            "unacked third capture must remain pending after announce",
            1,
            repo.outboxPending(TEST_PEER_DEVICE_ID).size,
        )

        val firstAck = listOf(
            OriginSequenceRanges(
                TEST_LOCAL_DEVICE_ID,
                listOf(SequenceRange(first.originSeq, second.originSeq)),
            ),
        )
        repo.ackRanges(TEST_PEER_DEVICE_ID, firstAck, NOW_MS + STEP_MS * 3)

        val outboxAfterFirst = repo.outboxPending(TEST_PEER_DEVICE_ID)
        val cursorsAfterFirst = repo.getPeerCursors(TEST_PEER_DEVICE_ID)
        val localCursorAfterFirst = cursorsAfterFirst[TEST_LOCAL_DEVICE_ID]
        assertEquals(
            "first ack of seq 1-2 must leave exactly one outbox row",
            1,
            outboxAfterFirst.size,
        )
        assertEquals(
            "remaining outbox row must be the unacked third capture",
            third.eventId,
            outboxAfterFirst.single().eventId,
        )
        assertTrue("peer cursor for local origin must exist after first ack", localCursorAfterFirst != null)
        assertEquals(
            "first ack must advance the peer cursor through seq 2",
            2L,
            localCursorAfterFirst!!.contiguousSeq,
        )
        assertTrue(
            "first ack must leave no holes above the contiguous cursor",
            localCursorAfterFirst.receivedRanges.isEmpty(),
        )

        repo.ackRanges(TEST_PEER_DEVICE_ID, firstAck, NOW_MS + STEP_MS * 4)

        val outboxAfterReplay = repo.outboxPending(TEST_PEER_DEVICE_ID)
        val cursorsAfterReplay = repo.getPeerCursors(TEST_PEER_DEVICE_ID)
        val localCursorAfterReplay = cursorsAfterReplay[TEST_LOCAL_DEVICE_ID]
        assertEquals(
            "second identical ack must not change outbox size",
            outboxAfterFirst.size,
            outboxAfterReplay.size,
        )
        assertEquals(
            "second identical ack must not drop the remaining outbox row",
            third.eventId,
            outboxAfterReplay.single().eventId,
        )
        assertTrue("peer cursor must still exist after replayed ack", localCursorAfterReplay != null)
        assertEquals(
            "second identical ack must not change the peer cursor",
            localCursorAfterFirst.contiguousSeq,
            localCursorAfterReplay!!.contiguousSeq,
        )
        assertEquals(
            "replayed ack must not invent received-range holes",
            localCursorAfterFirst.receivedRanges,
            localCursorAfterReplay.receivedRanges,
        )

        repo.ackRanges(
            TEST_PEER_DEVICE_ID,
            listOf(
                OriginSequenceRanges(
                    TEST_LOCAL_DEVICE_ID,
                    listOf(SequenceRange(third.originSeq, third.originSeq)),
                ),
            ),
            NOW_MS + STEP_MS * 5,
        )
        assertTrue(
            "acking the remaining seq must drain the outbox",
            repo.outboxPending(TEST_PEER_DEVICE_ID).isEmpty(),
        )
        assertEquals(
            "final ack must advance the peer cursor through seq 3",
            3L,
            repo.getPeerCursors(TEST_PEER_DEVICE_ID).getValue(TEST_LOCAL_DEVICE_ID).contiguousSeq,
        )
    }

    private suspend fun storeLocal(
        repo: ClipRepository,
        text: String,
        nowMs: Long,
        captureIndex: Int,
    ): CaptureResult.Stored {
        val captured = repo.captureLocalText(
            text,
            nowMs = nowMs,
            peerId = TEST_PEER_DEVICE_ID,
        )
        assertTrue("local capture must store at index $captureIndex", captured is CaptureResult.Stored)
        return captured as CaptureResult.Stored
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val STEP_MS = 3_000L
    }
}
