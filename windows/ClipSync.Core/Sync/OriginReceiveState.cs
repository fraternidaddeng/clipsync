namespace ClipSync.Core.Sync;

/// <summary>
/// Persisted receive progress for one origin device: the greatest fully contiguous sequence plus
/// isolated ranges persisted above a gap. Protocol v1 sections 4 and 6 define the invariants.
/// </summary>
public sealed class OriginReceiveState
{
    public static OriginReceiveState Empty { get; } = new(0, Array.Empty<SequenceRange>());

    public OriginReceiveState(long contiguousSeq, IReadOnlyList<SequenceRange> receivedRanges)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(contiguousSeq);
        ArgumentNullException.ThrowIfNull(receivedRanges);

        if (!SequenceRangeMath.IsCanonical(receivedRanges))
        {
            throw new ArgumentException("received_ranges must be sorted, disjoint, and non-adjacent.", nameof(receivedRanges));
        }

        if (receivedRanges.Count > 0 && receivedRanges[0].StartSeq <= contiguousSeq + 1)
        {
            throw new ArgumentException(
                "Every received range must start above contiguous_seq + 1; adjacent sequences advance the cursor instead.",
                nameof(receivedRanges));
        }

        ContiguousSeq = contiguousSeq;
        ReceivedRanges = receivedRanges;
    }

    public long ContiguousSeq { get; }

    public IReadOnlyList<SequenceRange> ReceivedRanges { get; }

    public bool Contains(long seq) =>
        seq <= ContiguousSeq || ReceivedRanges.Any(range => range.Contains(seq));

    /// <summary>Highest sequence this state claims, including isolated ranges above a gap.</summary>
    public long HighestCoveredSeq() =>
        Math.Max(ContiguousSeq, ReceivedRanges.Count == 0 ? 0 : ReceivedRanges.Max(range => range.EndSeq));

    public OriginReceiveState Accept(long seq)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(seq, 1);
        return AcceptRange(new SequenceRange(seq, seq));
    }

    /// <summary>Marks every sequence in <paramref name="range"/> as persisted, advancing the cursor through any ranges it now touches.</summary>
    public OriginReceiveState AcceptRange(SequenceRange range)
    {
        if (range.EndSeq <= ContiguousSeq)
        {
            return this;
        }

        if (range.StartSeq > ContiguousSeq + 1)
        {
            var merged = SequenceRangeMath.Normalize(ReceivedRanges.Append(range));
            if (merged.Count == ReceivedRanges.Count
                && merged.Zip(ReceivedRanges).All(pair => pair.First == pair.Second))
            {
                return this;
            }

            return new OriginReceiveState(ContiguousSeq, merged);
        }

        var contiguous = Math.Max(ContiguousSeq, range.EndSeq);
        var remaining = new List<SequenceRange>(ReceivedRanges);
        while (remaining.Count > 0 && remaining[0].StartSeq <= contiguous + 1)
        {
            contiguous = Math.Max(contiguous, remaining[0].EndSeq);
            remaining.RemoveAt(0);
        }

        return new OriginReceiveState(contiguous, remaining);
    }

    /// <summary>The full persisted coverage as a canonical range list: the contiguous prefix followed by isolated ranges.</summary>
    public IReadOnlyList<SequenceRange> ToCoverage()
    {
        if (ContiguousSeq == 0)
        {
            return ReceivedRanges;
        }

        var coverage = new List<SequenceRange>(ReceivedRanges.Count + 1)
        {
            new(1, ContiguousSeq)
        };
        coverage.AddRange(ReceivedRanges);
        return coverage;
    }

    /// <summary>Sequences the other peer holds that this state does not.</summary>
    public IReadOnlyList<SequenceRange> MissingFrom(OriginReceiveState theirs)
    {
        ArgumentNullException.ThrowIfNull(theirs);
        return SequenceRangeMath.Subtract(theirs.ToCoverage(), ToCoverage());
    }
}
