namespace ClipSync.Peer.Diagnostics;

/// <summary>One recorded diagnostic: a short status code and when it happened. Never carries clipboard content.</summary>
public sealed record DiagnosticEntry(DateTimeOffset TimestampUtc, string Code);

/// <summary>
/// Thread-safe, fixed-capacity ring buffer of recent diagnostic codes. It holds only status codes
/// and timestamps — never clipboard content, nonces, proofs, or secrets — so its contents are safe
/// to show in a local diagnostics viewer. The oldest entries drop once capacity is exceeded.
/// </summary>
public sealed class BoundedDiagnosticsLog
{
    private readonly int capacity;
    private readonly Queue<DiagnosticEntry> entries = new();
    private readonly object gate = new();

    public BoundedDiagnosticsLog(int capacity = 200)
    {
        if (capacity <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(capacity), "Capacity must be positive.");
        }

        this.capacity = capacity;
    }

    public int Capacity => capacity;

    public int Count
    {
        get
        {
            lock (gate)
            {
                return entries.Count;
            }
        }
    }

    /// <summary>Records a code. Blank codes are ignored; the buffer never grows beyond its capacity.</summary>
    public void Record(string code, DateTimeOffset timestampUtc)
    {
        if (string.IsNullOrWhiteSpace(code))
        {
            return;
        }

        lock (gate)
        {
            entries.Enqueue(new DiagnosticEntry(timestampUtc, code));
            while (entries.Count > capacity)
            {
                entries.Dequeue();
            }
        }
    }

    /// <summary>Point-in-time copy, newest first.</summary>
    public IReadOnlyList<DiagnosticEntry> Snapshot()
    {
        lock (gate)
        {
            var list = new List<DiagnosticEntry>(entries);
            list.Reverse();
            return list;
        }
    }

    public void Clear()
    {
        lock (gate)
        {
            entries.Clear();
        }
    }
}
