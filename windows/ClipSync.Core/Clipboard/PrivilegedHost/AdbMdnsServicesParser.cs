namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>One row of <c>adb mdns services</c>: an instance name, its service type, and where it lives.</summary>
public sealed record AdbMdnsServiceRow(string InstanceName, string ServiceType, WirelessAdbEndpoint? Endpoint)
{
    /// <summary>Type comparison ignoring the trailing dot adb sometimes prints (<c>_x._tcp.</c> vs <c>_x._tcp</c>).</summary>
    public bool IsOfType(string serviceType) =>
        string.Equals(
            ServiceType.TrimEnd('.'),
            serviceType.TrimEnd('.'),
            StringComparison.OrdinalIgnoreCase);
}

/// <summary>
/// Parses <c>adb mdns services</c> stdout into rows. Pure text handling, mirroring
/// <see cref="AdbDeviceListParser"/>: the discovery that makes the QR flow hands-free
/// (phone scans → phone announces <c>_adb-tls-pairing._tcp</c> → PC finds it here) stays
/// unit-testable without a network or an adb binary.
/// </summary>
public static class AdbMdnsServicesParser
{
    /// <summary>Announced by a phone whose wireless-debugging pairing dialog is open right now.</summary>
    public const string PairingServiceType = "_adb-tls-pairing._tcp";

    /// <summary>Announced by a phone with wireless debugging on; its endpoint is what <c>adb connect</c> wants.</summary>
    public const string ConnectServiceType = "_adb-tls-connect._tcp";

    /// <summary>
    /// Reads the service list. The header line and blanks are skipped; each remaining line is
    /// "<c>instance\ttype\thost:port</c>". A row whose endpoint does not parse is kept with a
    /// null endpoint rather than dropped, so diagnostics can still show what was seen.
    /// </summary>
    public static IReadOnlyList<AdbMdnsServiceRow> Parse(string? stdout)
    {
        if (string.IsNullOrWhiteSpace(stdout))
        {
            return Array.Empty<AdbMdnsServiceRow>();
        }

        var rows = new List<AdbMdnsServiceRow>();
        var lines = stdout.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        foreach (var rawLine in lines)
        {
            var line = rawLine.Trim();
            if (line.Length == 0
                || line.StartsWith("List of", StringComparison.OrdinalIgnoreCase)
                || line.StartsWith('*'))
            {
                continue;
            }

            // Columns are tab-separated in every adb that has this command; fall back to any
            // whitespace so an unexpected format degrades to best effort instead of nothing.
            var columns = line.Split('\t', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            if (columns.Length < 3)
            {
                columns = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            }

            if (columns.Length < 3)
            {
                continue;
            }

            var instance = columns[0];
            var serviceType = columns[1];
            // A row whose endpoint fails to parse is deliberately kept with Endpoint = null.
            var endpoint = WirelessAdbEndpoint.TryParse(columns[2], out var parsed) ? parsed : null;
            rows.Add(new AdbMdnsServiceRow(instance, serviceType, endpoint));
        }

        return rows;
    }
}
