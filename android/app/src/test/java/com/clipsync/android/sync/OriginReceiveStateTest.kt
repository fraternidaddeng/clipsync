package com.clipsync.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginReceiveStateTest {
    @Test
    fun acceptContiguousSequenceAdvancesCursor() {
        val state = OriginReceiveState.EMPTY.accept(1).accept(2).accept(3)
        assertEquals(3, state.contiguousSeq)
        assertTrue(state.receivedRanges.isEmpty())
    }

    @Test
    fun gapKeepsCursorAndRecordsIsolatedRange() {
        // Plan section 3.3: receiving seq 12 while 11 is missing must not move the cursor past 10.
        var state = OriginReceiveState.EMPTY
        for (seq in 1L..10L) {
            state = state.accept(seq)
        }
        state = state.accept(12)

        assertEquals(10, state.contiguousSeq)
        assertEquals(listOf(SequenceRange(12, 12)), state.receivedRanges)
        assertTrue(state.contains(12))
        assertFalse(state.contains(11))
    }

    @Test
    fun fillingGapMergesIsolatedRangeIntoCursor() {
        val state = OriginReceiveState.EMPTY.accept(1).accept(3).accept(4).accept(2)
        assertEquals(4, state.contiguousSeq)
        assertTrue(state.receivedRanges.isEmpty())
    }

    @Test
    fun acceptRangeBelowCursorIsNoOp() {
        val state = OriginReceiveState(5, emptyList())
        assertSame(state, state.acceptRange(SequenceRange(2, 4)))
    }

    @Test
    fun coverageIncludesContiguousPrefixAndIsolatedRanges() {
        val state = OriginReceiveState(3, listOf(SequenceRange(7, 8)))
        assertEquals(listOf(SequenceRange(1, 3), SequenceRange(7, 8)), state.toCoverage())
    }

    @Test
    fun missingFromReturnsTheirSequencesWeLack() {
        val ours = OriginReceiveState(3, listOf(SequenceRange(7, 8)))
        val theirs = OriginReceiveState(8, emptyList())
        assertEquals(listOf(SequenceRange(4, 6)), ours.missingFrom(theirs))
    }

    @Test
    fun constructorRejectsAdjacentRange() {
        try {
            OriginReceiveState(5, listOf(SequenceRange(6, 7)))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Adjacent sequences must advance the cursor instead of living in received_ranges.
        }
    }

    @Test
    fun constructorRejectsNonCanonicalRanges() {
        try {
            OriginReceiveState(0, listOf(SequenceRange(5, 6), SequenceRange(3, 4)))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Ranges must be sorted, disjoint, and non-adjacent.
        }
    }
}
