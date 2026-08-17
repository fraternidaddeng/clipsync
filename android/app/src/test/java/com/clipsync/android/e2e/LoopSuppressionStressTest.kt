package com.clipsync.android.e2e

import com.clipsync.android.notify.InboundClip
import com.clipsync.android.notify.InboundClipApplier
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.LOCAL_DEDUP_WINDOW_MS
import com.clipsync.android.storage.OriginSequenceRanges
import com.clipsync.android.storage.RemoteClipEvent
import com.clipsync.android.storage.RemoteStoreResult
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.storage.SequenceRange
import com.clipsync.android.storage.TEST_LOCAL_DEVICE_ID
import com.clipsync.android.storage.TEST_PEER_DEVICE_ID
import com.clipsync.android.storage.createTestClipRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1,000-cycle local-capture → outbox/ack → remote ingest → clipboard write → echo-suppress loop.
 * In-memory repository only; no network or Robolectric.
 */
class LoopSuppressionStressTest {
    @Test
    fun `one thousand capture sync writeback cycles keep sequences contiguous and swallow each echo once`() = runTest {
        val repo = createTestClipRepository()
        repo.initialize()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)

        val writer = FakeClipboardWriter()
        var clockMs = NOW_MS
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = writer,
            hasher = Sha256ContentHasher,
            nowEpochMillis = { clockMs },
        )
        val applier = InboundClipApplier(
            repository = repo,
            writeCoordinator = coordinator,
            offerManualCopy = { },
        )

        val inboundBodies = HashSet<String>(CYCLES)
        for (cycle in 1..CYCLES) {
            val localText = "cycle-$cycle"
            val captured = repo.captureLocalText(
                localText,
                sourceApp = "shizuku",
                nowMs = clockMs,
                peerId = TEST_PEER_DEVICE_ID,
            )
            assertTrue(
                "local capture must store at cycle $cycle",
                captured is CaptureResult.Stored,
            )
            val stored = captured as CaptureResult.Stored
            assertEquals(
                "local origin seq must be contiguous at cycle $cycle",
                cycle.toLong(),
                stored.originSeq,
            )

            val pending = repo.outboxPending(TEST_PEER_DEVICE_ID)
            assertTrue(
                "outbox must contain the captured row at cycle $cycle",
                pending.any { it.eventId == stored.eventId },
            )
            applyDeliveryAndAck(repo, stored.eventId, stored.originSeq, clockMs)
            assertTrue(
                "outbox must be empty after ack at cycle $cycle",
                repo.outboxPending(TEST_PEER_DEVICE_ID).isEmpty(),
            )

            val inboundText = "win-$cycle"
            inboundBodies += inboundText
            val eventId = "peer-evt-$cycle"
            val ingested = repo.ingestRemoteClip(
                RemoteClipEvent(
                    eventId = eventId,
                    originDeviceId = TEST_PEER_DEVICE_ID,
                    originSeq = cycle.toLong(),
                    content = inboundText,
                    contentHash = Sha256ContentHasher.hash(inboundText),
                    sourceApp = null,
                    createdAtMs = clockMs,
                ),
                sourcePeerId = TEST_PEER_DEVICE_ID,
            )
            assertTrue(
                "remote ingest must store at cycle $cycle",
                ingested is RemoteStoreResult.Stored,
            )

            applier.onCommitted(listOf(InboundClip(eventId, inboundText)))
            assertEquals(
                "inbound apply must write once at cycle $cycle",
                cycle,
                writer.writes.size,
            )
            assertEquals(
                "inbound apply must use the ingest event id at cycle $cycle",
                eventId,
                writer.writes.last().originEventId,
            )

            assertTrue(
                "echo must be suppressed exactly once at cycle $cycle",
                coordinator.shouldSuppressCapture(inboundText),
            )
            assertFalse(
                "consumed echo marker must not suppress a second time at cycle $cycle",
                coordinator.shouldSuppressCapture(inboundText),
            )
            assertFalse(
                "genuinely new local copy must not be suppressed at cycle $cycle",
                coordinator.shouldSuppressCapture("fresh-$cycle"),
            )

            clockMs += LOCAL_DEDUP_WINDOW_MS + 1L
        }

        val live = repo.search("")
        assertEquals(
            "live rows must equal local captures plus inbound events with no duplicates",
            CYCLES * 2,
            live.size,
        )

        val localRows = live.filter { it.originDeviceId == TEST_LOCAL_DEVICE_ID }
        val peerRows = live.filter { it.originDeviceId == TEST_PEER_DEVICE_ID }
        assertEquals("local origin must contribute one live row per cycle", CYCLES, localRows.size)
        assertEquals("peer origin must contribute one live row per cycle", CYCLES, peerRows.size)
        assertEquals(
            "local origin sequences must be strictly contiguous",
            (1L..CYCLES.toLong()).toList(),
            localRows.map { it.originSeq }.sorted(),
        )
        assertEquals(
            "peer origin sequences must be strictly contiguous",
            (1L..CYCLES.toLong()).toList(),
            peerRows.map { it.originSeq }.sorted(),
        )

        val vector = repo.knownVector()
        val localState = vector.origins[TEST_LOCAL_DEVICE_ID]
        val peerState = vector.origins[TEST_PEER_DEVICE_ID]
        assertTrue("knownVector must include the local origin", localState != null)
        assertTrue("knownVector must include the peer origin", peerState != null)
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
        assertTrue(
            "local knownVector must have no holes",
            localState.receivedRanges.isEmpty(),
        )
        assertTrue(
            "peer knownVector must have no holes",
            peerState.receivedRanges.isEmpty(),
        )

        assertTrue(
            "outbox must stay fully drained after the loop",
            repo.outboxPending(TEST_PEER_DEVICE_ID).isEmpty(),
        )
        assertTrue(
            "no inbound body may appear as a sourced echo row",
            live.none { it.content in inboundBodies && it.sourceApp != null },
        )
    }

    /**
     * Mirrors [com.clipsync.android.sync.SyncSessionEngine] drain + ACK_RANGES:
     * mark announced, then [com.clipsync.android.storage.ClipRepository.ackRanges].
     */
    private suspend fun applyDeliveryAndAck(
        repo: ClipRepository,
        eventId: String,
        originSeq: Long,
        nowMs: Long,
    ) {
        val pending = repo.outboxPending(TEST_PEER_DEVICE_ID)
        val announced = pending.filter { it.eventId == eventId }.map { it.id }
        if (announced.isNotEmpty()) {
            repo.markAnnounced(announced)
        }
        repo.ackRanges(
            TEST_PEER_DEVICE_ID,
            listOf(
                OriginSequenceRanges(
                    TEST_LOCAL_DEVICE_ID,
                    listOf(SequenceRange(originSeq, originSeq)),
                ),
            ),
            nowMs,
        )
    }

    private companion object {
        const val CYCLES = 1_000
        const val NOW_MS = 1_700_000_000_000L
    }
}
