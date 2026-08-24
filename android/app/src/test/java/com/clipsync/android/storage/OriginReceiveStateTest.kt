package com.clipsync.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginReceiveStateTest {
    @Test
    fun `empty state has zero cursor and no ranges`() {
        assertEquals(0, OriginReceiveState.EMPTY.contiguousSeq)
        assertTrue(OriginReceiveState.EMPTY.receivedRanges.isEmpty())
        assertFalse(OriginReceiveState.EMPTY.contains(1))
    }

    @Test
    fun `receiving 12 while 11 is missing leaves contiguous at 10`() {
        val state = OriginReceiveState(10, emptyList()).accept(12)
        assertEquals(10, state.contiguousSeq)
        assertEquals(listOf(SequenceRange(12, 12)), state.receivedRanges)
    }

    @Test
    fun `filling the gap advances through the isolated range`() {
        val state = OriginReceiveState(10, listOf(SequenceRange(12, 12))).accept(11)
        assertEquals(12, state.contiguousSeq)
        assertTrue(state.receivedRanges.isEmpty())
    }

    @Test
    fun `contiguous prefix from 1 is absorbed without leftover ranges`() {
        val state = OriginReceiveState.EMPTY.accept(1).accept(2).accept(3)
        assertEquals(3, state.contiguousSeq)
        assertTrue(state.receivedRanges.isEmpty())
    }

    @Test
    fun `accepting an already-covered sequence is a no-op`() {
        val state = OriginReceiveState(5, listOf(SequenceRange(8, 9)))
        assertEquals(state, state.accept(3))
        assertEquals(state, state.accept(8))
    }

    @Test
    fun `highest covered seq uses isolated ranges above the cursor`() {
        assertEquals(0, OriginReceiveState.EMPTY.highestCoveredSeq())
        assertEquals(10, OriginReceiveState(10, emptyList()).highestCoveredSeq())
        assertEquals(14, OriginReceiveState(10, listOf(SequenceRange(13, 14))).highestCoveredSeq())
    }

    @Test
    fun `range json round-trips protocol field names`() {
        val ranges = listOf(SequenceRange(5, 6), SequenceRange(9, 12))
        val restored = SequenceRangeJson.deserialize(SequenceRangeJson.serialize(ranges))
        assertEquals(ranges, restored)
        assertEquals("[]", SequenceRangeJson.serialize(emptyList()))
        assertTrue(SequenceRangeJson.deserialize("[]").isEmpty())
    }
}
