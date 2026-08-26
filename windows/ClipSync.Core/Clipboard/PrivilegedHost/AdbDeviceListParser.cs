namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// Parses <c>adb devices -l</c> stdout into <see cref="AndroidAdbDevice"/> rows. Pure text
/// handling so the state-word mapping and the model-hint extraction stay unit-testable
/// without a device or an adb binary present.
/// </summary>
public static class AdbDeviceListParser
{
    /// <summary>
    /// Reads the long-form device list. The first line ("List of devices attached") and any
    /// blank or daemon-chatter lines are skipped; every remaining line is
    /// "<c>serial\tstate key:value …</c>". Unknown state words become
    /// <see cref="AdbDeviceState.Unknown"/> rather than being dropped, so a future adb never
    /// makes a connected phone silently vanish from the UI.
    /// </summary>
    public static IReadOnlyList<AndroidAdbDevice> Parse(string? stdout)
    {
        if (string.IsNullOrWhiteSpace(stdout))
        {
            return Array.Empty<AndroidAdbDevice>();
        }

        var devices = new List<AndroidAdbDevice>();
        var lines = stdout.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        foreach (var rawLine in lines)
        {
            var line = rawLine.Trim();
            if (line.Length == 0
                || line.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase)
                || line.StartsWith('*')
                || line.StartsWith("adb server", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            // Columns are whitespace-separated: serial, state, then optional key:value hints.
            var columns = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (columns.Length < 2)
            {
                continue;
            }

            var serial = columns[0];
            var state = MapState(columns[1]);
            var model = ExtractHint(columns, "model:");
            devices.Add(new AndroidAdbDevice(serial, state, model));
        }

        return devices;
    }

    private static AdbDeviceState MapState(string token)
    {
        if (Equals(token, "device"))
        {
            return AdbDeviceState.Ready;
        }

        if (Equals(token, "unauthorized"))
        {
            return AdbDeviceState.Unauthorized;
        }

        if (Equals(token, "offline"))
        {
            return AdbDeviceState.Offline;
        }

        return AdbDeviceState.Unknown;

        static bool Equals(string value, string expected) =>
            string.Equals(value, expected, StringComparison.OrdinalIgnoreCase);
    }

    private static string? ExtractHint(IReadOnlyList<string> columns, string prefix)
    {
        for (var i = 2; i < columns.Count; i++)
        {
            if (columns[i].StartsWith(prefix, StringComparison.Ordinal))
            {
                var value = columns[i][prefix.Length..].Replace('_', ' ').Trim();
                return value.Length == 0 ? null : value;
            }
        }

        return null;
    }
}
