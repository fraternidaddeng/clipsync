package com.clipsync.android.sync

import com.clipsync.android.storage.OriginReceiveState
import com.clipsync.android.storage.SequenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRangeMathTest {
    @Test
    fun `missingFrom is the peer sequences local OriginReceiveState does not contain`() {
        val local = OriginReceiveState(10, emptyList())
        val peer = OriginReceiveState(12, listOf(SequenceRange(14, 14)))
        assertEquals(
            listOf(SequenceRange(11, 12), SequenceRange(14, 14)),
            SyncRangeMath.missingFrom(local, peer),
        )
        assertTrue(local.contains(10))
        assertTrue(!local.contains(11))
        assertTrue(peer.contains(12) && peer.contains(14))
    }

    @Test
    fun `missingFrom is empty when local already covers the peer vector`() {
        val local = OriginReceiveState(12, listOf(SequenceRange(14, 16)))
        val peer = OriginReceiveState(10, listOf(SequenceRange(14, 14)))
        assertTrue(SyncRangeMath.missingFrom(local, peer).isEmpty())
    }

    @Test
    fun `take caps sequences per origin for want_ranges`() {
        val missing = listOf(SequenceRange(1, 10), SequenceRange(20, 25))
        assertEquals(listOf(SequenceRange(1, 3)), SyncRangeMath.take(missing, 3))
        assertEquals(16, SyncRangeMath.totalCount(missing))
        assertEquals(3, SyncRangeMath.totalCount(SyncRangeMath.take(missing, 3)))
    }

    @Test
    fun `coverage is contiguous prefix plus isolated ranges`() {
        val empty = OriginReceiveState.EMPTY
        assertTrue(SyncRangeMath.coverage(empty).isEmpty())
        val gapped = OriginReceiveState(10, listOf(SequenceRange(12, 12)))
        assertEquals(
            listOf(SequenceRange(1, 10), SequenceRange(12, 12)),
            SyncRangeMath.coverage(gapped),
        )
    }
}
