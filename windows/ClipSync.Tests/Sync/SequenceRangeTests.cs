using ClipSync.Core.Sync;

namespace ClipSync.Tests.Sync;

public sealed class SequenceRangeTests
{
    [Fact]
    public void ConstructorRejectsStartBelowOne()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new SequenceRange(0, 5));
    }

    [Fact]
    public void ConstructorRejectsEndBelowStart()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new SequenceRange(7, 6));
    }

    [Fact]
    public void CountAndContainsUseInclusiveBounds()
    {
        var range = new SequenceRange(3, 5);

        Assert.Equal(3, range.Count);
        Assert.False(range.Contains(2));
        Assert.True(range.Contains(3));
        Assert.True(range.Contains(5));
        Assert.False(range.Contains(6));
    }

    [Fact]
    public void NormalizeSortsMergesOverlapAndAdjacency()
    {
        var normalized = SequenceRangeMath.Normalize(new[]
        {
            new SequenceRange(10, 12),
            new SequenceRange(1, 2),
            new SequenceRange(3, 4),
            new SequenceRange(11, 15),
            new SequenceRange(20, 20)
        });

        Assert.Equal(new[] { new SequenceRange(1, 4), new SequenceRange(10, 15), new SequenceRange(20, 20) }, normalized);
    }

    [Fact]
    public void IsCanonicalRejectsUnsortedOverlappingOrAdjacentLists()
    {
        Assert.True(SequenceRangeMath.IsCanonical(Array.Empty<SequenceRange>()));
        Assert.True(SequenceRangeMath.IsCanonical(new[] { new SequenceRange(1, 3), new SequenceRange(5, 9) }));
        Assert.False(SequenceRangeMath.IsCanonical(new[] { new SequenceRange(5, 9), new SequenceRange(1, 3) }));
        Assert.False(SequenceRangeMath.IsCanonical(new[] { new SequenceRange(1, 5), new SequenceRange(4, 9) }));
        Assert.False(SequenceRangeMath.IsCanonical(new[] { new SequenceRange(1, 3), new SequenceRange(4, 9) }));
    }

    [Fact]
    public void SubtractRemovesCoveredSequences()
    {
        var minuend = new[] { new SequenceRange(1, 10), new SequenceRange(15, 20) };
        var subtrahend = new[] { new SequenceRange(2, 3), new SequenceRange(9, 16), new SequenceRange(19, 19) };

        var difference = SequenceRangeMath.Subtract(minuend, subtrahend);

        Assert.Equal(
            new[]
            {
                new SequenceRange(1, 1),
                new SequenceRange(4, 8),
                new SequenceRange(17, 18),
                new SequenceRange(20, 20)
            },
            difference);
    }

    [Fact]
    public void SubtractHandlesHoleSpanningMultipleMinuendRanges()
    {
        var minuend = new[] { new SequenceRange(1, 4), new SequenceRange(6, 9) };
        var subtrahend = new[] { new SequenceRange(3, 7) };

        var difference = SequenceRangeMath.Subtract(minuend, subtrahend);

        Assert.Equal(new[] { new SequenceRange(1, 2), new SequenceRange(8, 9) }, difference);
    }

    [Fact]
    public void SubtractWithEmptySubtrahendReturnsMinuend()
    {
        var minuend = new[] { new SequenceRange(4, 6) };

        Assert.Equal(minuend, SequenceRangeMath.Subtract(minuend, Array.Empty<SequenceRange>()));
    }

    [Fact]
    public void TakeLimitsTotalSequenceCount()
    {
        var ranges = new[] { new SequenceRange(1, 5), new SequenceRange(10, 14) };

        var capped = SequenceRangeMath.Take(ranges, 7);

        Assert.Equal(new[] { new SequenceRange(1, 5), new SequenceRange(10, 11) }, capped);
        Assert.Equal(ranges, SequenceRangeMath.Take(ranges, 10));
        Assert.Equal(ranges, SequenceRangeMath.Take(ranges, 100));
        Assert.Empty(SequenceRangeMath.Take(ranges, 0));
    }

    [Fact]
    public void TotalCountSumsInclusiveRangeSizes()
    {
        Assert.Equal(0, SequenceRangeMath.TotalCount(Array.Empty<SequenceRange>()));
        Assert.Equal(10, SequenceRangeMath.TotalCount(new[] { new SequenceRange(1, 5), new SequenceRange(10, 14) }));
    }
}
