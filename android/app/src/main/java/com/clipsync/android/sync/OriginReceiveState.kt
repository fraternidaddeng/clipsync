package com.clipsync.android.sync

/**
 * Persisted receive progress for one origin device: the greatest fully contiguous sequence plus
 * isolated ranges persisted above a gap. Protocol v1 sections 4 and 6 define the invariants;
 * this mirrors the Windows `OriginReceiveState` so both peers compute identical vectors.
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
            "Every received range must start above contiguous_seq + 1; adjacent sequences advance the cursor instead."
        }
    }

    fun contains(seq: Long): Boolean =
        seq <= contiguousSeq || receivedRanges.any { it.contains(seq) }

    fun accept(seq: Long): OriginReceiveState {
        require(seq >= 1) { "Sequences start at 1." }
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

    /** The full persisted coverage as a canonical range list: the contiguous prefix followed by isolated ranges. */
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

    override fun equals(other: Any?): Boolean =
        other is OriginReceiveState &&
            other.contiguousSeq == contiguousSeq &&
            other.receivedRanges == receivedRanges

    override fun hashCode(): Int = 31 * contiguousSeq.hashCode() + receivedRanges.hashCode()

    override fun toString(): String =
        "OriginReceiveState(contiguousSeq=$contiguousSeq, receivedRanges=$receivedRanges)"

    companion object {
        val EMPTY = OriginReceiveState(0, emptyList())
    }
}
