package com.clipsync.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceRangesTest {
    private fun ranges(vararg pairs: Pair<Long, Long>): List<SequenceRange> =
        pairs.map { SequenceRange(it.first, it.second) }

    @Test
    fun `normalize merges overlapping and adjacent ranges`() {
        assertEquals(
            ranges(1L to 6L, 9L to 10L),
            SequenceRangeMath.normalize(ranges(4L to 6L, 1L to 3L, 2L to 5L, 9L to 9L, 10L to 10L)),
        )
    }

    @Test
    fun `isCanonical rejects adjacency and overlap`() {
        assertTrue(SequenceRangeMath.isCanonical(ranges(1L to 3L, 5L to 7L)))
        assertFalse(SequenceRangeMath.isCanonical(ranges(1L to 3L, 4L to 7L)))
        assertFalse(SequenceRangeMath.isCanonical(ranges(1L to 5L, 4L to 7L)))
    }

    @Test
    fun `subtract carves holes across ranges`() {
        assertEquals(
            ranges(1L to 1L, 5L to 6L, 10L to 12L),
            SequenceRangeMath.subtract(ranges(1L to 6L, 10L to 12L), ranges(2L to 4L, 7L to 9L)),
        )
        assertEquals(emptyList<SequenceRange>(), SequenceRangeMath.subtract(ranges(1L to 5L), ranges(1L to 9L)))
        assertEquals(ranges(1L to 5L), SequenceRangeMath.subtract(ranges(1L to 5L), emptyList()))
    }

    @Test
    fun `take caps the total sequence count`() {
        assertEquals(
            ranges(1L to 3L, 10L to 11L),
            SequenceRangeMath.take(ranges(1L to 3L, 10L to 20L), 5),
        )
        assertEquals(emptyList<SequenceRange>(), SequenceRangeMath.take(ranges(1L to 3L), 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ranges start at one`() {
        SequenceRange(0, 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reversed ranges are rejected`() {
        SequenceRange(5, 4)
    }

    @Test
    fun `accept advances the cursor through persisted isolated ranges`() {
        // Persisted 1..10 and 12: receiving 11 advances straight through 12 (protocol section 6).
        val state = OriginReceiveState(10, ranges(12L to 12L))
        val advanced = state.accept(11)
        assertEquals(12, advanced.contiguousSeq)
        assertTrue(advanced.receivedRanges.isEmpty())
    }

    @Test
    fun `accept above a gap stores an isolated range`() {
        val state = OriginReceiveState.EMPTY.accept(1).accept(5)
        assertEquals(1, state.contiguousSeq)
        assertEquals(ranges(5L to 5L), state.receivedRanges)
        assertTrue(state.contains(5))
        assertFalse(state.contains(4))
    }

    @Test
    fun `coverage and missingFrom drive want computation`() {
        val mine = OriginReceiveState(3, ranges(6L to 6L))
        val theirs = OriginReceiveState(8, emptyList())
        assertEquals(ranges(4L to 5L, 7L to 8L), mine.missingFrom(theirs))
        assertEquals(ranges(1L to 3L, 6L to 6L), mine.toCoverage())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `received ranges adjacent to the cursor are invalid`() {
        OriginReceiveState(10, ranges(11L to 12L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-canonical received ranges are invalid`() {
        OriginReceiveState(0, ranges(5L to 8L, 6L to 9L))
    }
}
