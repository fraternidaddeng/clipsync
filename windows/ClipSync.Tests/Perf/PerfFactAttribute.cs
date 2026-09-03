using System.Diagnostics;
using System.Globalization;
using Xunit.Abstractions;

namespace ClipSync.Tests.Perf;

/// <summary>
/// Opt-in measurement: the fact only runs when <c>CLIPSYNC_PERF=1</c> is set, so the
/// regular suite stays fast and deterministic while the numbers in
/// docs/performance-audit.md remain reproducible with one command.
/// </summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = false)]
public sealed class PerfFactAttribute : FactAttribute
{
    public const string EnvironmentVariable = "CLIPSYNC_PERF";

    public PerfFactAttribute()
    {
        if (!string.Equals(Environment.GetEnvironmentVariable(EnvironmentVariable), "1", StringComparison.Ordinal))
        {
            Skip = $"Set {EnvironmentVariable}=1 to run performance measurements.";
        }
    }
}

/// <summary>Stopwatch + allocation probe shared by the perf facts. Reports median of N runs.</summary>
internal static class PerfProbe
{
    public static async Task<PerfSample> MeasureAsync(
        string label,
        int iterations,
        Func<Task> body,
        ITestOutputHelper output)
    {
        ArgumentNullException.ThrowIfNull(body);
        ArgumentNullException.ThrowIfNull(output);
        ArgumentOutOfRangeException.ThrowIfLessThan(iterations, 1);

        await body().ConfigureAwait(false);

        var elapsed = new double[iterations];
        var allocated = new long[iterations];
        for (var index = 0; index < iterations; index++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            var before = GC.GetTotalAllocatedBytes(precise: true);
            var stopwatch = Stopwatch.StartNew();
            await body().ConfigureAwait(false);
            stopwatch.Stop();
            allocated[index] = GC.GetTotalAllocatedBytes(precise: true) - before;
            elapsed[index] = stopwatch.Elapsed.TotalMilliseconds;
        }

        var sample = new PerfSample(label, Median(elapsed), elapsed.Min(), elapsed.Max(), Median(allocated.Select(value => (double)value).ToArray()));
        output.WriteLine(sample.ToString());
        return sample;
    }

    public static PerfSample Measure(string label, int iterations, Action body, ITestOutputHelper output)
    {
        ArgumentNullException.ThrowIfNull(body);
        return MeasureAsync(
            label,
            iterations,
            () =>
            {
                body();
                return Task.CompletedTask;
            },
            output).GetAwaiter().GetResult();
    }

    private static double Median(double[] values)
    {
        var sorted = values.Order().ToArray();
        var middle = sorted.Length / 2;
        return sorted.Length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }
}

internal sealed record PerfSample(string Label, double MedianMs, double MinMs, double MaxMs, double MedianAllocatedBytes)
{
    public override string ToString() =>
        string.Format(
            CultureInfo.InvariantCulture,
            "{0,-56} median {1,9:F3} ms  (min {2,9:F3} / max {3,9:F3})  alloc {4,10:N0} B",
            Label,
            MedianMs,
            MinMs,
            MaxMs,
            MedianAllocatedBytes);
}
