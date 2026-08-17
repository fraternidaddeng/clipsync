package com.clipsync.android.sync

import com.clipsync.android.storage.OriginReceiveState
import com.clipsync.android.storage.SequenceRange

/**
 * Missing-range math for want_ranges. Lives in the sync package so Agent D does
 * not invent a second Room schema; coverage is derived from
 * [OriginReceiveState.contains] invariants (contiguous prefix + isolated ranges).
 *
 * Port of windows/ClipSync.Core/Sync/SequenceRange.cs Subtract/Take plus
 * OriginReceiveState.MissingFrom.
 */
object SyncRangeMath {
    fun coverage(state: OriginReceiveState): List<SequenceRange> {
        if (state.contiguousSeq == 0L) {
            return state.receivedRanges
        }
        return listOf(SequenceRange(1, state.contiguousSeq)) + state.receivedRanges
    }

    /** Sequences [peer] holds that [local] does not. */
    fun missingFrom(local: OriginReceiveState, peer: OriginReceiveState): List<SequenceRange> =
        subtract(coverage(peer), coverage(local))

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
                cursor = hole.endSeq + 1
            }
        }
        return result
    }

    fun take(ranges: List<SequenceRange>, maximumSequences: Long): List<SequenceRange> {
        require(maximumSequences >= 0) { "maximumSequences cannot be negative." }
        val result = mutableListOf<SequenceRange>()
        var remaining = maximumSequences
        for (range in ranges) {
            if (remaining <= 0L) {
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
