namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// One <c>host:port</c> target for adb's wireless-debugging commands (<c>adb pair</c> /
/// <c>adb connect</c>). Pure value parsing so the user-typed "IP:端口" field and the
/// mDNS-discovered endpoints share one validator and its tests never launch adb.
/// </summary>
public sealed record WirelessAdbEndpoint(string Host, int Port)
{
    /// <summary>The exact token adb expects: <c>host:port</c>, IPv6 hosts re-bracketed.</summary>
    public override string ToString() =>
        Host.Contains(':', StringComparison.Ordinal) ? $"[{Host}]:{Port}" : $"{Host}:{Port}";

    /// <summary>
    /// Parses a <c>host:port</c> string as typed by a user or printed by adb. Accepts IPv4,
    /// host names, and bracketed IPv6 (<c>[fe80::1]:37123</c>). A bare IPv6 without brackets
    /// is rejected rather than guessed — the last colon-group would silently become a wrong
    /// port. Whitespace around the value is ignored.
    /// </summary>
    public static bool TryParse(string? input, out WirelessAdbEndpoint? endpoint)
    {
        endpoint = null;
        var text = input?.Trim();
        if (string.IsNullOrEmpty(text))
        {
            return false;
        }

        string host;
        string portToken;
        if (text.StartsWith('['))
        {
            var close = text.IndexOf(']', StringComparison.Ordinal);
            if (close <= 1 || close + 1 >= text.Length || text[close + 1] != ':')
            {
                return false;
            }

            host = text[1..close];
            portToken = text[(close + 2)..];
        }
        else
        {
            var colon = text.IndexOf(':', StringComparison.Ordinal);
            // Exactly one colon: more means an unbracketed IPv6 (ambiguous), zero means no port.
            if (colon <= 0 || colon != text.LastIndexOf(':'))
            {
                return false;
            }

            host = text[..colon];
            portToken = text[(colon + 1)..];
        }

        if (host.Length == 0 || host.Any(char.IsWhiteSpace))
        {
            return false;
        }

        if (portToken.Length == 0
            || !portToken.All(char.IsAsciiDigit)
            || !int.TryParse(portToken, out var port)
            || port is < 1 or > 65535)
        {
            return false;
        }

        endpoint = new WirelessAdbEndpoint(host, port);
        return true;
    }
}
