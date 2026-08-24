package com.clipsync.android.sync

/** A closed, inclusive origin-sequence range as defined by protocol v1 section 6. */
data class SequenceRange(val startSeq: Long, val endSeq: Long) {
    init {
        require(startSeq >= 1) { "Sequence ranges start at 1." }
        require(endSeq >= startSeq) { "end_seq cannot be below start_seq." }
    }

    val count: Long get() = endSeq - startSeq + 1

    fun contains(seq: Long): Boolean = seq in startSeq..endSeq
}

/** Canonical range-list arithmetic shared with the Windows peer (SequenceRangeMath.cs). */
object SequenceRangeMath {
    /** Sorts and merges overlapping or adjacent ranges into the canonical protocol form. */
    fun normalize(ranges: Iterable<SequenceRange>): List<SequenceRange> {
        val result = mutableListOf<SequenceRange>()
        for (range in ranges.sortedBy { it.startSeq }) {
            val last = result.lastOrNull()
            if (last != null && range.startSeq <= last.endSeq + 1) {
                if (range.endSeq > last.endSeq) {
                    result[result.size - 1] = SequenceRange(last.startSeq, range.endSeq)
                }
                continue
            }
            result.add(range)
        }
        return result
    }

    /** True when the list is sorted by start, non-overlapping, and non-adjacent. */
    fun isCanonical(ranges: List<SequenceRange>): Boolean {
        for (index in 1 until ranges.size) {
            if (ranges[index].startSeq <= ranges[index - 1].endSeq + 1) {
                return false
            }
        }
        return true
    }

    /** Sequences covered by [minuend] but not by [subtrahend]. Both inputs must be canonical. */
    fun subtract(minuend: List<SequenceRange>, subtrahend: List<SequenceRange>): List<SequenceRange> {
        val result = mutableListOf<SequenceRange>()
        var holeIndex = 0
        for (range in minuend) {
            var cursor = range.startSeq
            while (cursor <= range.endSeq) {
                while (holeIndex < subtrahend.size && subtrahend[holeIndex].endSeq < cursor) {
                    holeIndex++
                }
                if (holeIndex >= subtrahend.size || subtrahend[holeIndex].startSeq > range.endSeq) {
                    result.add(SequenceRange(cursor, range.endSeq))
                    break
                }
                val hole = subtrahend[holeIndex]
                if (hole.startSeq > cursor) {
                    result.add(SequenceRange(cursor, hole.startSeq - 1))
                }
                // A hole can span into the next minuend range, so it is not consumed here.
                cursor = hole.endSeq + 1
            }
        }
        return result
    }

    /** Caps a canonical range list to at most [maximumSequences] total sequences. */
    fun take(ranges: List<SequenceRange>, maximumSequences: Long): List<SequenceRange> {
        require(maximumSequences >= 0)
        val result = mutableListOf<SequenceRange>()
        var remaining = maximumSequences
        for (range in ranges) {
            if (remaining <= 0) {
                break
            }
            if (range.count <= remaining) {
                result.add(range)
                remaining -= range.count
                continue
            }
            result.add(SequenceRange(range.startSeq, range.startSeq + remaining - 1))
            break
        }
        return result
    }

    fun totalCount(ranges: List<SequenceRange>): Long = ranges.sumOf { it.count }
}

/**
 * Receive progress for one origin device: the greatest fully contiguous sequence plus isolated
 * ranges persisted above a gap. Mirrors the Windows OriginReceiveState so both peers compute
 * identical vectors (protocol v1 sections 4 and 6).
 */
class OriginReceiveState(
    val contiguousSeq: Long,
    val receivedRanges: List<SequenceRange>,
) {
    init {
        require(contiguousSeq >= 0) { "contiguous_seq cannot be negative." }
        require(SequenceRangeMath.isCanonical(receivedRanges)) {
            "received_ranges must be sorted, disjoint, and non-adjacent."
        }
        require(receivedRanges.isEmpty() || receivedRanges[0].startSeq > contiguousSeq + 1) {
            "Every received range must start above contiguous_seq + 1."
        }
    }

    fun contains(seq: Long): Boolean =
        seq <= contiguousSeq || receivedRanges.any { it.contains(seq) }

    fun accept(seq: Long): OriginReceiveState {
        require(seq >= 1)
        return acceptRange(SequenceRange(seq, seq))
    }

    /** Marks every sequence in [range] as persisted, advancing the cursor through any ranges it now touches. */
    fun acceptRange(range: SequenceRange): OriginReceiveState {
        if (range.endSeq <= contiguousSeq) {
            return this
        }
        if (range.startSeq > contiguousSeq + 1) {
            val merged = SequenceRangeMath.normalize(receivedRanges + range)
            if (merged == receivedRanges) {
                return this
            }
            return OriginReceiveState(contiguousSeq, merged)
        }
        var contiguous = maxOf(contiguousSeq, range.endSeq)
        val remaining = receivedRanges.toMutableList()
        while (remaining.isNotEmpty() && remaining[0].startSeq <= contiguous + 1) {
            contiguous = maxOf(contiguous, remaining[0].endSeq)
            remaining.removeAt(0)
        }
        return OriginReceiveState(contiguous, remaining)
    }

    /** The full persisted coverage: the contiguous prefix followed by isolated ranges. */
    fun toCoverage(): List<SequenceRange> {
        if (contiguousSeq == 0L) {
            return receivedRanges
        }
        return buildList(receivedRanges.size + 1) {
            add(SequenceRange(1, contiguousSeq))
            addAll(receivedRanges)
        }
    }

    /** Sequences the other peer holds that this state does not. */
    fun missingFrom(theirs: OriginReceiveState): List<SequenceRange> =
        SequenceRangeMath.subtract(theirs.toCoverage(), toCoverage())

    companion object {
        val EMPTY = OriginReceiveState(0, emptyList())
    }
}
