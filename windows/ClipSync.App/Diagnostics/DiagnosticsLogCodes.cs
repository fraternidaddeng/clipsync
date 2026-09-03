using System.Globalization;
using System.Text;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Bluetooth;
using ClipSync.Peer.Pairing;
using Microsoft.Extensions.Logging;

namespace ClipSync.App.Diagnostics;

/// <summary>
/// Maps our <see cref="ILogger"/> events to diagnostics codes. A code is the event name in
/// snake_case under a prefix chosen by the logger category — <c>peer_</c> for the IP peer
/// (<c>ClipSync.Peer.*</c>), <c>bt_</c> for the Bluetooth fallback
/// (<c>ClipSync.Peer.Bluetooth*</c>), <c>app_</c> for anything else under <c>ClipSync.</c> —
/// so a Bluetooth <c>SessionEnded</c> never collides with the IP one. A state value follows
/// the code only when that value belongs to a closed set of constants declared in code (error
/// codes, rate-limit kinds, loop names, session outcomes) or is the listening port. Formatted
/// messages, device ids, display names, addresses, MACs, exception-kind strings, and free-text
/// reasons never reach a code — anything outside an allow list reads <c>_unknown</c>.
/// </summary>
internal static class DiagnosticsLogCodes
{
    private const string AdmittedCategoryPrefix = "ClipSync.";
    private const string PeerCategoryPrefix = "ClipSync.Peer";
    private const string BluetoothCategoryPrefix = "ClipSync.Peer.Bluetooth";
    private const string PeerCodePrefix = "peer_";
    private const string BluetoothCodePrefix = "bt_";
    private const string AppCodePrefix = "app_";
    private const string UnknownSuffix = "_unknown";
    private const string PortSuffixPrefix = "_port_";
    private const string ServerListeningEvent = "ServerListening";
    private const string PortProperty = "Port";

    /// <summary>Session end/frame/peer-error codes: the v1 and v2 protocol vocabularies, plus the "no error" marker.</summary>
    private static readonly HashSet<string> SessionCodes = new(
        ProtocolErrorCodes.All.Concat(ProtocolErrorCodes.V2).Append("none"),
        StringComparer.Ordinal);

    /// <summary>Per event name: the state property that may follow the code and the values allowed to.</summary>
    private static readonly Dictionary<string, AllowList> AllowLists = new(StringComparer.Ordinal)
    {
        ["PairingConfirmFailed"] = new("Code", PairingErrorCodes.All),
        ["SessionEnded"] = new("Code", SessionCodes),
        ["FrameRejected"] = new("Code", SessionCodes),
        ["PeerError"] = new("Code", SessionCodes),
        ["ConnectionRateLimited"] = new("Kind", new HashSet<string>(StringComparer.Ordinal) { "sync_accept", "pairing_confirm" }),
        ["StoreConflict"] = new("Stage", new HashSet<string>(StringComparer.Ordinal) { "terminal", "announce", "payload", "image_payload" }),
        ["BackgroundLoopStopped"] = new("Loop", new HashSet<string>(StringComparer.Ordinal) { "ping", "outbox" }),
    };

    /// <summary>
    /// Bluetooth-category overrides, consulted before <see cref="AllowLists"/>. The inner bt1
    /// session runs the same engine as the IP path (so its <c>PeerLog</c> events fall through
    /// to the shared table), while <c>BluetoothLog</c>'s own events carry bt1 handshake codes
    /// and the host's session outcomes. <c>ListenerFailed</c>/<c>HandshakeAborted</c> carry an
    /// exception-kind string and <c>HandshakeRefused</c> a free-text reason: neither is listed.
    /// </summary>
    private static readonly Dictionary<string, AllowList> BluetoothAllowLists = new(StringComparer.Ordinal)
    {
        ["HandshakeRefused"] = new("Code", Bt1ErrorCodes.WireCodes),
        ["SessionEnded"] = new("Code", new HashSet<string>(SessionCodes.Concat(BluetoothLog.SessionEndOutcomes), StringComparer.Ordinal)),
    };

    /// <summary>Only our own categories are recorded; Kestrel/ASP.NET chatter carries paths and addresses.</summary>
    public static bool IsAdmittedCategory(string category) =>
        category.StartsWith(AdmittedCategoryPrefix, StringComparison.Ordinal);

    /// <summary>The code prefix for an admitted category; null for foreign categories.</summary>
    internal static string? PrefixFor(string category)
    {
        if (!IsAdmittedCategory(category))
        {
            return null;
        }

        if (MatchesCategory(category, BluetoothCategoryPrefix))
        {
            return BluetoothCodePrefix;
        }

        return MatchesCategory(category, PeerCategoryPrefix) ? PeerCodePrefix : AppCodePrefix;
    }

    /// <summary>
    /// The diagnostics code for one log event, or null when the category is not ours. The
    /// formatter output is deliberately not an input: only the event id, allow-listed state
    /// values, and the exception's type name can shape the result.
    /// </summary>
    public static string? For(
        string category,
        EventId eventId,
        IReadOnlyList<KeyValuePair<string, object?>>? state,
        Exception? exception = null)
    {
        var prefix = PrefixFor(category);
        if (prefix is null)
        {
            return null;
        }

        var name = eventId.Name;
        var builder = new StringBuilder(prefix);
        if (string.IsNullOrEmpty(name))
        {
            builder.Append("event_").Append(eventId.Id);
        }
        else
        {
            builder.Append(ToSnakeCase(name)).Append(Suffix(prefix, name, state));
        }

        if (exception is not null)
        {
            builder.Append('_').Append(exception.GetType().Name);
        }

        return builder.ToString();
    }

    /// <summary>"ClipSync.Peer" matches "ClipSync.Peer" and "ClipSync.Peer.X", never "ClipSync.PeerX".</summary>
    private static bool MatchesCategory(string category, string categoryPrefix) =>
        category.StartsWith(categoryPrefix, StringComparison.Ordinal)
        && (category.Length == categoryPrefix.Length || category[categoryPrefix.Length] == '.');

    private static string Suffix(string prefix, string eventName, IReadOnlyList<KeyValuePair<string, object?>>? state)
    {
        if (string.Equals(eventName, ServerListeningEvent, StringComparison.Ordinal))
        {
            return Find(state, PortProperty) is int port and >= 0 and <= ushort.MaxValue
                ? PortSuffixPrefix + port.ToString(CultureInfo.InvariantCulture)
                : PortSuffixPrefix + "unknown";
        }

        var listed = string.Equals(prefix, BluetoothCodePrefix, StringComparison.Ordinal)
            && BluetoothAllowLists.TryGetValue(eventName, out var bluetoothAllowList)
            ? bluetoothAllowList
            : AllowLists.GetValueOrDefault(eventName);
        if (listed is null)
        {
            return string.Empty;
        }

        return Find(state, listed.Property) is string value && listed.Values.Contains(value)
            ? "_" + value.ToLowerInvariant()
            : UnknownSuffix;
    }

    private static object? Find(IReadOnlyList<KeyValuePair<string, object?>>? state, string property)
    {
        if (state is null)
        {
            return null;
        }

        foreach (var pair in state)
        {
            if (string.Equals(pair.Key, property, StringComparison.OrdinalIgnoreCase))
            {
                return pair.Value;
            }
        }

        return null;
    }

    /// <summary>PascalCase event name to snake_case; anything outside [a-z0-9] becomes an underscore.</summary>
    internal static string ToSnakeCase(string name)
    {
        var builder = new StringBuilder(name.Length + 8);
        for (var index = 0; index < name.Length; index++)
        {
            var current = name[index];
            if (char.IsAsciiLetterUpper(current))
            {
                var startsWord = index > 0
                    && (!char.IsAsciiLetterUpper(name[index - 1])
                        || (index + 1 < name.Length && char.IsAsciiLetterLower(name[index + 1])));
                if (startsWord)
                {
                    builder.Append('_');
                }

                builder.Append(char.ToLowerInvariant(current));
            }
            else if (char.IsAsciiLetterLower(current) || char.IsAsciiDigit(current))
            {
                builder.Append(current);
            }
            else
            {
                builder.Append('_');
            }
        }

        return builder.ToString();
    }

    private sealed record AllowList(string Property, IReadOnlySet<string> Values);
}
