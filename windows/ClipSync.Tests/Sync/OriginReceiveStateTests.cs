using ClipSync.Core.Sync;

namespace ClipSync.Tests.Sync;

public sealed class OriginReceiveStateTests
{
    [Fact]
    public void EmptyStateHasZeroCursorAndNoRanges()
    {
        Assert.Equal(0, OriginReceiveState.Empty.ContiguousSeq);
        Assert.Empty(OriginReceiveState.Empty.ReceivedRanges);
        Assert.False(OriginReceiveState.Empty.Contains(1));
    }

    [Fact]
    public void ConstructorRejectsRangesNotAboveCursorGap()
    {
        // start_seq must be greater than contiguous_seq + 1; 11 would extend the cursor instead.
        Assert.Throws<ArgumentException>(() =>
            new OriginReceiveState(10, new[] { new SequenceRange(11, 12) }));
        Assert.Throws<ArgumentException>(() =>
            new OriginReceiveState(10, new[] { new SequenceRange(9, 12) }));
    }

    [Fact]
    public void ConstructorRejectsNonCanonicalRanges()
    {
        Assert.Throws<ArgumentException>(() =>
            new OriginReceiveState(0, new[] { new SequenceRange(5, 6), new SequenceRange(2, 3) }));
        Assert.Throws<ArgumentException>(() =>
            new OriginReceiveState(0, new[] { new SequenceRange(2, 3), new SequenceRange(4, 6) }));
    }

    [Fact]
    public void AcceptOutOfOrderSequenceDoesNotAdvanceCursorPastGap()
    {
        // The acceptance case from plan.md: receiving 12 while 11 is missing keeps the cursor at 10.
        var state = new OriginReceiveState(10, Array.Empty<SequenceRange>()).Accept(12);

        Assert.Equal(10, state.ContiguousSeq);
        Assert.Equal(new[] { new SequenceRange(12, 12) }, state.ReceivedRanges);
        Assert.True(state.Contains(12));
        Assert.False(state.Contains(11));
    }

    [Fact]
    public void AcceptGapFillAdvancesCursorThroughIsolatedRanges()
    {
        var state = new OriginReceiveState(10, new[] { new SequenceRange(12, 12) }).Accept(11);

        Assert.Equal(12, state.ContiguousSeq);
        Assert.Empty(state.ReceivedRanges);
    }

    [Fact]
    public void AcceptNextSequenceAdvancesCursor()
    {
        var state = OriginReceiveState.Empty.Accept(1).Accept(2).Accept(3);

        Assert.Equal(3, state.ContiguousSeq);
        Assert.Empty(state.ReceivedRanges);
    }

    [Fact]
    public void AcceptAlreadyContainedSequenceReturnsSameState()
    {
        var state = new OriginReceiveState(5, new[] { new SequenceRange(8, 9) });

        Assert.Same(state, state.Accept(3));
        Assert.Same(state, state.Accept(8));
    }

    [Fact]
    public void AcceptMergesTouchingIsolatedRanges()
    {
        var state = new OriginReceiveState(0, new[] { new SequenceRange(3, 4), new SequenceRange(6, 7) }).Accept(5);

        Assert.Equal(0, state.ContiguousSeq);
        Assert.Equal(new[] { new SequenceRange(3, 7) }, state.ReceivedRanges);
    }

    [Fact]
    public void AcceptRangeCoversWholeRangeAtOnce()
    {
        var state = OriginReceiveState.Empty
            .AcceptRange(new SequenceRange(4, 6))
            .AcceptRange(new SequenceRange(1, 3));

        Assert.Equal(6, state.ContiguousSeq);
        Assert.Empty(state.ReceivedRanges);
    }

    [Fact]
    public void HighestCoveredSeqUsesIsolatedRangesAboveTheCursor()
    {
        Assert.Equal(0, OriginReceiveState.Empty.HighestCoveredSeq());
        Assert.Equal(10, new OriginReceiveState(10, Array.Empty<SequenceRange>()).HighestCoveredSeq());
        Assert.Equal(14, new OriginReceiveState(10, new[] { new SequenceRange(13, 14) }).HighestCoveredSeq());
    }

    [Fact]
    public void CoverageListsContiguousPrefixThenIsolatedRanges()
    {
        var state = new OriginReceiveState(4, new[] { new SequenceRange(7, 8) });

        Assert.Equal(new[] { new SequenceRange(1, 4), new SequenceRange(7, 8) }, state.ToCoverage());
        Assert.Empty(OriginReceiveState.Empty.ToCoverage());
    }

    [Fact]
    public void MissingFromComputesWhatOtherPeerHasAndWeLack()
    {
        var ours = new OriginReceiveState(10, new[] { new SequenceRange(13, 14) });
        var theirs = new OriginReceiveState(14, Array.Empty<SequenceRange>());

        var missing = ours.MissingFrom(theirs);

        Assert.Equal(new[] { new SequenceRange(11, 12) }, missing);
        Assert.Empty(theirs.MissingFrom(ours));
    }

    [Fact]
    public void RangeListRoundTripsThroughJson()
    {
        var ranges = new[] { new SequenceRange(5, 6), new SequenceRange(9, 12) };

        var restored = SequenceRangeJson.Deserialize(SequenceRangeJson.Serialize(ranges));

        Assert.Equal(ranges, restored);
        Assert.Empty(SequenceRangeJson.Deserialize("[]"));
        Assert.Equal("[]", SequenceRangeJson.Serialize(Array.Empty<SequenceRange>()));
    }

    [Fact]
    public void RangeListJsonRejectsInvalidPayloads()
    {
        Assert.Throws<ArgumentException>(() => SequenceRangeJson.Deserialize("{"));
        Assert.Throws<ArgumentException>(() => SequenceRangeJson.Deserialize("""[{"start_seq":0,"end_seq":2}]"""));
        Assert.Throws<ArgumentException>(() => SequenceRangeJson.Deserialize("""[{"start_seq":5,"end_seq":2}]"""));
    }
}
