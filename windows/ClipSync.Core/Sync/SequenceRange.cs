using System.Text.Json;

namespace ClipSync.Core.Sync;

/// <summary>A closed, inclusive origin-sequence range as defined by protocol v1 section 6.</summary>
public readonly record struct SequenceRange
{
    public SequenceRange(long startSeq, long endSeq)
    {
        if (startSeq < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(startSeq), "Sequence ranges start at 1.");
        }

        if (endSeq < startSeq)
        {
            throw new ArgumentOutOfRangeException(nameof(endSeq), "end_seq cannot be below start_seq.");
        }

        StartSeq = startSeq;
        EndSeq = endSeq;
    }

    public long StartSeq { get; }

    public long EndSeq { get; }

    public long Count => EndSeq - StartSeq + 1;

    public bool Contains(long seq) => seq >= StartSeq && seq <= EndSeq;
}

public static class SequenceRangeMath
{
    /// <summary>Sorts and merges overlapping or adjacent ranges into the canonical protocol form.</summary>
    public static IReadOnlyList<SequenceRange> Normalize(IEnumerable<SequenceRange> ranges)
    {
        ArgumentNullException.ThrowIfNull(ranges);

        var result = new List<SequenceRange>();
        foreach (var range in ranges.OrderBy(range => range.StartSeq))
        {
            if (result.Count > 0 && range.StartSeq <= result[^1].EndSeq + 1)
            {
                if (range.EndSeq > result[^1].EndSeq)
                {
                    result[^1] = new SequenceRange(result[^1].StartSeq, range.EndSeq);
                }

                continue;
            }

            result.Add(range);
        }

        return result;
    }

    /// <summary>True when the list is sorted by start, non-overlapping, and non-adjacent.</summary>
    public static bool IsCanonical(IReadOnlyList<SequenceRange> ranges)
    {
        ArgumentNullException.ThrowIfNull(ranges);

        for (var index = 1; index < ranges.Count; index++)
        {
            if (ranges[index].StartSeq <= ranges[index - 1].EndSeq + 1)
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>Returns the sequences covered by <paramref name="minuend"/> but not by <paramref name="subtrahend"/>. Both inputs must be canonical.</summary>
    public static IReadOnlyList<SequenceRange> Subtract(
        IReadOnlyList<SequenceRange> minuend,
        IReadOnlyList<SequenceRange> subtrahend)
    {
        ArgumentNullException.ThrowIfNull(minuend);
        ArgumentNullException.ThrowIfNull(subtrahend);

        var result = new List<SequenceRange>();
        var holeIndex = 0;
        foreach (var range in minuend)
        {
            var cursor = range.StartSeq;
            while (cursor <= range.EndSeq)
            {
                while (holeIndex < subtrahend.Count && subtrahend[holeIndex].EndSeq < cursor)
                {
                    holeIndex++;
                }

                if (holeIndex >= subtrahend.Count || subtrahend[holeIndex].StartSeq > range.EndSeq)
                {
                    result.Add(new SequenceRange(cursor, range.EndSeq));
                    break;
                }

                var hole = subtrahend[holeIndex];
                if (hole.StartSeq > cursor)
                {
                    result.Add(new SequenceRange(cursor, hole.StartSeq - 1));
                }

                // A hole can span into the next minuend range, so it is not consumed here.
                cursor = hole.EndSeq + 1;
            }
        }

        return result;
    }

    /// <summary>Caps a canonical range list to at most <paramref name="maximumSequences"/> total sequences.</summary>
    public static IReadOnlyList<SequenceRange> Take(IReadOnlyList<SequenceRange> ranges, long maximumSequences)
    {
        ArgumentNullException.ThrowIfNull(ranges);
        ArgumentOutOfRangeException.ThrowIfNegative(maximumSequences);

        var result = new List<SequenceRange>();
        var remaining = maximumSequences;
        foreach (var range in ranges)
        {
            if (remaining <= 0)
            {
                break;
            }

            if (range.Count <= remaining)
            {
                result.Add(range);
                remaining -= range.Count;
                continue;
            }

            result.Add(new SequenceRange(range.StartSeq, range.StartSeq + remaining - 1));
            break;
        }

        return result;
    }

    public static long TotalCount(IReadOnlyList<SequenceRange> ranges)
    {
        ArgumentNullException.ThrowIfNull(ranges);
        return ranges.Sum(range => range.Count);
    }
}

/// <summary>Serializes canonical range lists for SQLite columns using protocol field names.</summary>
public static class SequenceRangeJson
{
    public static string Serialize(IReadOnlyList<SequenceRange> ranges)
    {
        ArgumentNullException.ThrowIfNull(ranges);

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartArray();
            foreach (var range in ranges)
            {
                writer.WriteStartObject();
                writer.WriteNumber("start_seq", range.StartSeq);
                writer.WriteNumber("end_seq", range.EndSeq);
                writer.WriteEndObject();
            }

            writer.WriteEndArray();
        }

        return System.Text.Encoding.UTF8.GetString(stream.ToArray());
    }

    public static IReadOnlyList<SequenceRange> Deserialize(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);

        try
        {
            using var document = JsonDocument.Parse(json);
            if (document.RootElement.ValueKind != JsonValueKind.Array)
            {
                throw new ArgumentException("A range list must be a JSON array.", nameof(json));
            }

            var ranges = new List<SequenceRange>();
            foreach (var element in document.RootElement.EnumerateArray())
            {
                ranges.Add(new SequenceRange(
                    element.GetProperty("start_seq").GetInt64(),
                    element.GetProperty("end_seq").GetInt64()));
            }

            return ranges;
        }
        catch (Exception exception) when (exception is JsonException or KeyNotFoundException or ArgumentOutOfRangeException or InvalidOperationException)
        {
            throw new ArgumentException("The persisted range list is invalid.", nameof(json), exception);
        }
    }
}
