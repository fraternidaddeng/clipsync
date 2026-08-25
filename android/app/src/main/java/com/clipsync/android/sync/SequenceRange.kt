package com.clipsync.android.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A closed, inclusive origin-sequence range as defined by protocol v1 section 6. */
data class SequenceRange(val startSeq: Long, val endSeq: Long) {
    init {
        require(startSeq >= 1) { "Sequence ranges start at 1." }
        require(endSeq >= startSeq) { "end_seq cannot be below start_seq." }
    }

    val count: Long get() = endSeq - startSeq + 1

    fun contains(seq: Long): Boolean = seq in startSeq..endSeq
}

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

    /** Sequences covered by [minuend] but not by [subtrahend]; both inputs must be canonical. */
    fun subtract(
        minuend: List<SequenceRange>,
        subtrahend: List<SequenceRange>,
    ): List<SequenceRange> {
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
        require(maximumSequences >= 0) { "maximumSequences cannot be negative." }
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
 * Serializes canonical range lists for Room columns using protocol field names. The persisted
 * format matches the Windows store byte-for-byte so a schema dump is comparable across peers.
 */
object SequenceRangeJson {
    @Serializable
    private data class RangeDocument(
        @SerialName("start_seq") val startSeq: Long,
        @SerialName("end_seq") val endSeq: Long,
    )

    private val json = Json

    fun serialize(ranges: List<SequenceRange>): String =
        json.encodeToString(
            ListSerializer(RangeDocument.serializer()),
            ranges.map { RangeDocument(it.startSeq, it.endSeq) },
        )

    fun deserialize(value: String): List<SequenceRange> {
        require(value.isNotBlank()) { "The persisted range list cannot be blank." }
        return try {
            json.decodeFromString(ListSerializer(RangeDocument.serializer()), value)
                .map { SequenceRange(it.startSeq, it.endSeq) }
        } catch (exception: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("The persisted range list is invalid.", exception)
        }
    }
}
