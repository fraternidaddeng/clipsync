package com.clipsync.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceRangeMathTest {
    @Test
    fun rangeRejectsStartBelowOne() {
        assertThrows { SequenceRange(0, 5) }
    }

    @Test
    fun rangeRejectsEndBelowStart() {
        assertThrows { SequenceRange(5, 4) }
    }

    @Test
    fun countIsInclusive() {
        assertEquals(1, SequenceRange(7, 7).count)
        assertEquals(5, SequenceRange(3, 7).count)
    }

    @Test
    fun normalizeMergesOverlappingAndAdjacentRanges() {
        val normalized = SequenceRangeMath.normalize(
            listOf(
                SequenceRange(8, 9),
                SequenceRange(1, 3),
                SequenceRange(4, 5),
                SequenceRange(2, 4),
            ),
        )
        assertEquals(listOf(SequenceRange(1, 5), SequenceRange(8, 9)), normalized)
    }

    @Test
    fun isCanonicalRequiresSortedDisjointNonAdjacent() {
        assertTrue(SequenceRangeMath.isCanonical(emptyList()))
        assertTrue(SequenceRangeMath.isCanonical(listOf(SequenceRange(1, 3), SequenceRange(5, 6))))
        assertFalse(SequenceRangeMath.isCanonical(listOf(SequenceRange(1, 3), SequenceRange(4, 6))))
        assertFalse(SequenceRangeMath.isCanonical(listOf(SequenceRange(5, 6), SequenceRange(1, 3))))
    }

    @Test
    fun subtractReturnsSequencesOnlyInMinuend() {
        val missing = SequenceRangeMath.subtract(
            minuend = listOf(SequenceRange(1, 10)),
            subtrahend = listOf(SequenceRange(1, 3), SequenceRange(6, 7)),
        )
        assertEquals(listOf(SequenceRange(4, 5), SequenceRange(8, 10)), missing)
    }

    @Test
    fun subtractHandlesHoleSpanningRanges() {
        val missing = SequenceRangeMath.subtract(
            minuend = listOf(SequenceRange(1, 4), SequenceRange(6, 9)),
            subtrahend = listOf(SequenceRange(3, 7)),
        )
        assertEquals(listOf(SequenceRange(1, 2), SequenceRange(8, 9)), missing)
    }

    @Test
    fun takeCapsTotalSequences() {
        val capped = SequenceRangeMath.take(
            listOf(SequenceRange(1, 5), SequenceRange(10, 20)),
            maximumSequences = 8,
        )
        assertEquals(listOf(SequenceRange(1, 5), SequenceRange(10, 12)), capped)
        assertEquals(8, SequenceRangeMath.totalCount(capped))
    }

    @Test
    fun jsonRoundTripUsesProtocolFieldNames() {
        val ranges = listOf(SequenceRange(2, 4), SequenceRange(9, 9))
        val json = SequenceRangeJson.serialize(ranges)
        assertEquals("""[{"start_seq":2,"end_seq":4},{"start_seq":9,"end_seq":9}]""", json)
        assertEquals(ranges, SequenceRangeJson.deserialize(json))
    }

    @Test
    fun jsonDeserializeRejectsMalformedInput() {
        assertThrows { SequenceRangeJson.deserialize("not json") }
        assertThrows { SequenceRangeJson.deserialize("") }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
