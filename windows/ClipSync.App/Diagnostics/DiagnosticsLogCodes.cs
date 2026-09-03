using System.Globalization;
using System.Text;
using ClipSync.Core.Protocol;
using ClipSync.Peer.Pairing;
using Microsoft.Extensions.Logging;

namespace ClipSync.App.Diagnostics;

/// <summary>
/// Maps Peer-layer <see cref="ILogger"/> events to diagnostics codes. A code is the event
/// name in snake_case under a <c>peer_</c> prefix; a state value follows it only when that
/// value belongs to a closed set of constants declared in code (error codes, rate-limit
/// kinds, loop names) or is the listening port. Formatted messages, device ids, display
/// names, addresses, and free-text details never reach a code — anything outside an allow
/// list reads <c>_unknown</c>.
/// </summary>
internal static class DiagnosticsLogCodes
{
    private const string AdmittedCategoryPrefix = "ClipSync.";
    private const string CodePrefix = "peer_";
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

    /// <summary>Only our own categories are recorded; Kestrel/ASP.NET chatter carries paths and addresses.</summary>
    public static bool IsAdmittedCategory(string category) =>
        category.StartsWith(AdmittedCategoryPrefix, StringComparison.Ordinal);

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
        if (!IsAdmittedCategory(category))
        {
            return null;
        }

        var name = eventId.Name;
        var builder = new StringBuilder(CodePrefix);
        if (string.IsNullOrEmpty(name))
        {
            builder.Append("event_").Append(eventId.Id);
        }
        else
        {
            builder.Append(ToSnakeCase(name)).Append(Suffix(name, state));
        }

        if (exception is not null)
        {
            builder.Append('_').Append(exception.GetType().Name);
        }

        return builder.ToString();
    }

    private static string Suffix(string eventName, IReadOnlyList<KeyValuePair<string, object?>>? state)
    {
        if (string.Equals(eventName, ServerListeningEvent, StringComparison.Ordinal))
        {
            return Find(state, PortProperty) is int port and >= 0 and <= ushort.MaxValue
                ? PortSuffixPrefix + port.ToString(CultureInfo.InvariantCulture)
                : PortSuffixPrefix + "unknown";
        }

        if (!AllowLists.TryGetValue(eventName, out var allowList))
        {
            return string.Empty;
        }

        return Find(state, allowList.Property) is string value && allowList.Values.Contains(value)
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
