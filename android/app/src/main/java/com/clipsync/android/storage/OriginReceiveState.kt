package com.clipsync.android.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Closed inclusive origin-sequence range (protocol v1 section 6).
 */
data class SequenceRange(val startSeq: Long, val endSeq: Long) {
    init {
        require(startSeq >= 1) { "Sequence ranges start at 1." }
        require(endSeq >= startSeq) { "end_seq cannot be below start_seq." }
    }

    val count: Long get() = endSeq - startSeq + 1

    fun contains(seq: Long): Boolean = seq in startSeq..endSeq
}

object SequenceRangeMath {
    fun normalize(ranges: Iterable<SequenceRange>): List<SequenceRange> {
        val result = mutableListOf<SequenceRange>()
        for (range in ranges.sortedBy { it.startSeq }) {
            val last = result.lastOrNull()
            if (last != null && range.startSeq <= last.endSeq + 1) {
                if (range.endSeq > last.endSeq) {
                    result[result.lastIndex] = SequenceRange(last.startSeq, range.endSeq)
                }
                continue
            }
            result.add(range)
        }
        return result
    }

    fun isCanonical(ranges: List<SequenceRange>): Boolean {
        for (index in 1 until ranges.size) {
            if (ranges[index].startSeq <= ranges[index - 1].endSeq + 1) {
                return false
            }
        }
        return true
    }
}

@Serializable
private data class SequenceRangeDto(
    @SerialName("start_seq") val startSeq: Long,
    @SerialName("end_seq") val endSeq: Long,
)

object SequenceRangeJson {
    private val json = Json { ignoreUnknownKeys = false }

    fun serialize(ranges: List<SequenceRange>): String =
        json.encodeToString(ranges.map { SequenceRangeDto(it.startSeq, it.endSeq) })

    fun deserialize(raw: String): List<SequenceRange> {
        require(raw.isNotBlank()) { "A range list must be a JSON array." }
        return try {
            json.decodeFromString<List<SequenceRangeDto>>(raw).map { SequenceRange(it.startSeq, it.endSeq) }
        } catch (error: Exception) {
            throw IllegalArgumentException("The persisted range list is invalid.", error)
        }
    }
}

/**
 * Greatest contiguous persisted sequence plus isolated ranges above a gap.
 * Receiving seq=12 while 11 is missing leaves contiguous at 10 and records {12,12}.
 */
data class OriginReceiveState(
    val contiguousSeq: Long,
    val receivedRanges: List<SequenceRange>,
) {
    init {
        require(contiguousSeq >= 0) { "contiguous_seq cannot be negative." }
        require(SequenceRangeMath.isCanonical(receivedRanges)) {
            "received_ranges must be sorted, disjoint, and non-adjacent."
        }
        if (receivedRanges.isNotEmpty() && receivedRanges[0].startSeq <= contiguousSeq + 1) {
            throw IllegalArgumentException(
                "Every received range must start above contiguous_seq + 1; adjacent sequences advance the cursor instead.",
            )
        }
    }

    fun contains(seq: Long): Boolean =
        seq <= contiguousSeq || receivedRanges.any { it.contains(seq) }

    fun accept(seq: Long): OriginReceiveState {
        require(seq >= 1) { "Sequences begin at 1." }
        return acceptRange(SequenceRange(seq, seq))
    }

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

    companion object {
        val EMPTY: OriginReceiveState = OriginReceiveState(0, emptyList())
    }
}
