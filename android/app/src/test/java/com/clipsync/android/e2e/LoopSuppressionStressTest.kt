package com.clipsync.android.e2e

import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.sync.InMemorySyncRepository
import com.clipsync.android.sync.OriginSequenceRanges
import com.clipsync.android.sync.RemoteClipEvent
import com.clipsync.android.sync.RemoteStoreResult
import com.clipsync.android.sync.SequenceRange
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1,000-cycle local-capture -> outbox announce/ack -> remote ingest -> clipboard write ->
 * echo-suppress loop over the production [InMemorySyncRepository] and
 * [ClipboardWriteCoordinator]. The outbox steps mirror the [com.clipsync.android.sync.SyncEngine]
 * drain (mark announced, then the peer's ACK_RANGES via applyPeerAckRanges). No network, no
 * Robolectric.
 */
class LoopSuppressionStressTest {
    @Test
    fun `one thousand capture sync writeback cycles keep sequences contiguous and swallow each echo once`() = runTest {
        val repo = InMemorySyncRepository(LOCAL_ID)
        var clockMs = NOW_MS
        val writer = FakeClipboardWriter()
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = writer,
            hasher = Sha256ContentHasher,
            nowEpochMillis = { clockMs },
        )

        for (cycle in 1..CYCLES) {
            // 1. Local capture allocates the next contiguous origin sequence and queues it.
            val localText = "cycle-$cycle"
            val captured = repo.recordLocalClip(localText, sourceApp = "shizuku", nowMs = clockMs)
            assertNotNull("local capture must store at cycle $cycle", captured)
            assertEquals(
                "local origin seq must be contiguous at cycle $cycle",
                cycle.toLong(),
                captured!!.originSeq,
            )

            // 2. Engine drain: announce the pending row, then the peer acks the sequence.
            val batch = repo.getOutboxBatch(PEER_ID, 16)
            assertTrue(
                "outbox must contain the captured row at cycle $cycle",
                batch.any { it.event.eventId == captured.eventId },
            )
            repo.markOutboxAnnounced(batch.map { it.entryId })
            repo.applyPeerAckRanges(
                PEER_ID,
                listOf(
                    OriginSequenceRanges(
                        LOCAL_ID,
                        listOf(SequenceRange(captured.originSeq, captured.originSeq)),
                    ),
                ),
                clockMs,
            )
            repo.resetOutboxToPending(PEER_ID)
            assertTrue(
                "acked rows must not resurface after reset at cycle $cycle",
                repo.getOutboxBatch(PEER_ID, 16).isEmpty(),
            )

            // 3. Remote ingest of the peer's clip for this cycle; the replay is a duplicate.
            val inboundText = "win-$cycle"
            val eventId = "peer-evt-$cycle"
            val remote = RemoteClipEvent(
                eventId = eventId,
                originDeviceId = PEER_ID,
                originSeq = cycle.toLong(),
                content = inboundText,
                contentHash = Sha256ContentHasher.hash(inboundText),
                sourceApp = null,
                createdAtMs = clockMs,
                expiresAtMs = null,
            )
            assertTrue(
                "remote ingest must store at cycle $cycle",
                repo.storeRemoteEvent(remote, viaDeviceId = PEER_ID) is RemoteStoreResult.Stored,
            )
            assertTrue(
                "replayed remote ingest must be idempotent at cycle $cycle",
                repo.storeRemoteEvent(remote, viaDeviceId = PEER_ID) is RemoteStoreResult.Duplicate,
            )

            // 4. Auto-apply writes the inbound body to the clipboard exactly once.
            val outcome = coordinator.writeText(inboundText, eventId)
            assertEquals(
                "inbound apply must succeed at cycle $cycle",
                ClipboardWriteResult.Success,
                outcome.result,
            )
            assertEquals("inbound apply must write once at cycle $cycle", cycle, writer.writes.size)
            assertEquals(
                "inbound apply must use the ingest event id at cycle $cycle",
                eventId,
                writer.writes.last().originEventId,
            )

            // 5. The capture pipeline swallows the echo of our own write exactly once.
            assertTrue(
                "echo must be suppressed exactly once at cycle $cycle",
                coordinator.shouldSuppressContent(inboundText),
            )
            assertFalse(
                "consumed echo marker must not suppress a second time at cycle $cycle",
                coordinator.shouldSuppressContent(inboundText),
            )
            assertFalse(
                "genuinely new local copy must not be suppressed at cycle $cycle",
                coordinator.shouldSuppressContent("fresh-$cycle"),
            )

            clockMs += CLOCK_STEP_MS
        }

        // Both origins stayed strictly contiguous with no received-range holes.
        val vector = repo.knownVector()
        val localState = vector[LOCAL_ID]
        val peerState = vector[PEER_ID]
        assertNotNull("knownVector must include the local origin", localState)
        assertNotNull("knownVector must include the peer origin", peerState)
        assertEquals(
            "local knownVector must stay strictly contiguous",
            CYCLES.toLong(),
            localState!!.contiguousSeq,
        )
        assertEquals(
            "peer knownVector must stay strictly contiguous",
            CYCLES.toLong(),
            peerState!!.contiguousSeq,
        )
        assertTrue("local knownVector must have no holes", localState.receivedRanges.isEmpty())
        assertTrue("peer knownVector must have no holes", peerState.receivedRanges.isEmpty())

        // The clipboard saw each inbound body exactly once, and the outbox is fully drained.
        assertEquals("one clipboard write per cycle", CYCLES, writer.writes.size)
        assertEquals(
            "clipboard writes must be unique",
            CYCLES,
            writer.writes.map { it.text }.toSet().size,
        )
        assertTrue(
            "outbox must stay fully drained after the loop",
            repo.getOutboxBatch(PEER_ID, 16).isEmpty(),
        )
    }

    private companion object {
        const val CYCLES = 1_000
        const val NOW_MS = 1_700_000_000_000L

        /** Above the coordinator's 5s suppression window so stale markers cannot pile up. */
        const val CLOCK_STEP_MS = 6_000L
        const val LOCAL_ID = "22222222-2222-4222-8222-222222222222"
        const val PEER_ID = "11111111-1111-4111-8111-111111111111"
    }
}
