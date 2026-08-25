namespace ClipSync.Spike.Bt1Windows;

/// <summary>
/// Console logging for the phase 0 spike. Every measurement that the report template
/// (docs/bluetooth-phase0-report-template.md) needs is emitted as one machine-parseable
/// line of the form <c>SPIKE_RESULT:key=value</c>; everything else is timestamped
/// free-form context. Never log secrets, nonces, proofs, or derived keys.
/// </summary>
internal static class SpikeLog
{
    public static void Info(string message) =>
        Console.WriteLine(FormattableString.Invariant($"[{DateTimeOffset.Now:HH:mm:ss.fff}] {message}"));

    public static void Result(string key, string value) =>
        Console.WriteLine(FormattableString.Invariant($"SPIKE_RESULT:{key}={value}"));

    public static void Result(string key, long value) =>
        Result(key, value.ToString(System.Globalization.CultureInfo.InvariantCulture));

    public static void Result(string key, double value) =>
        Result(key, value.ToString("F1", System.Globalization.CultureInfo.InvariantCulture));

    public static void Result(string key, bool value) => Result(key, value ? "true" : "false");
}
