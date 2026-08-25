using System.Text;
using System.Text.Json;

namespace ClipSync.Core.Protocol;

/// <summary>
/// Strict protocol v2 frame reader. v1 <see cref="ProtocolReader"/> stays frozen
/// and continues to reject version 2 and image payload types.
/// </summary>
public static class ProtocolReaderV2
{
    private static readonly Dictionary<string, Type> BodyTypes = new(StringComparer.Ordinal)
    {
        [ProtocolMessageTypes.Hello] = typeof(HelloBody),
        [ProtocolMessageTypes.Challenge] = typeof(ChallengeBody),
        [ProtocolMessageTypes.Auth] = typeof(AuthBody),
        [ProtocolMessageTypes.KnownVector] = typeof(SyncStateDto),
        [ProtocolMessageTypes.WantRanges] = typeof(WantRangesBody),
        [ProtocolMessageTypes.ClipAnnounce] = typeof(ClipAnnounceBody),
        [ProtocolMessageTypes.ClipFetch] = typeof(ClipFetchBody),
        [ProtocolMessageTypes.ClipPayload] = typeof(ClipPayloadBody),
        [ProtocolMessageTypes.ClipPayloadBegin] = typeof(ClipPayloadBeginBody),
        [ProtocolMessageTypes.ClipPayloadChunk] = typeof(ClipPayloadChunkBody),
        [ProtocolMessageTypes.ClipPayloadEnd] = typeof(ClipPayloadEndBody),
        [ProtocolMessageTypes.AckRanges] = typeof(AckRangesBody),
        [ProtocolMessageTypes.Error] = typeof(ErrorBody),
        [ProtocolMessageTypes.Ping] = typeof(PingBody),
        [ProtocolMessageTypes.Pong] = typeof(PongBody)
    };

    public static ProtocolParseOutcome Parse(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        return Parse(Encoding.UTF8.GetBytes(text));
    }

    public static ProtocolParseOutcome Parse(ReadOnlySpan<byte> utf8)
    {
        var scanFailure = ProtocolReader.ScanStrictJson(utf8);
        if (scanFailure is not null)
        {
            var code = scanFailure.Contains("malformed", StringComparison.OrdinalIgnoreCase)
                || scanFailure.Contains("duplicate", StringComparison.OrdinalIgnoreCase)
                ? ProtocolErrorCodes.MalformedJson
                : ProtocolErrorCodes.SchemaViolation;
            return new ProtocolParseOutcome.Failure(code, scanFailure);
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(utf8.ToArray(), new JsonDocumentOptions { MaxDepth = ProtocolLimits.MaxJsonDepth });
        }
        catch (JsonException)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.MalformedJson, "document parse failed");
        }

        using (document)
        {
            return ParseEnvelope(document.RootElement);
        }
    }

    private static ProtocolParseOutcome ParseEnvelope(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "envelope must be an object");
        }

        JsonElement? versionElement = null;
        JsonElement? typeElement = null;
        JsonElement? requestIdElement = null;
        JsonElement? bodyElement = null;
        foreach (var property in root.EnumerateObject())
        {
            switch (property.Name)
            {
                case "version":
                    versionElement = property.Value;
                    break;
                case "type":
                    typeElement = property.Value;
                    break;
                case "request_id":
                    requestIdElement = property.Value;
                    break;
                case "body":
                    bodyElement = property.Value;
                    break;
                default:
                    return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "unknown envelope field");
            }
        }

        if (versionElement is null || typeElement is null || requestIdElement is null || bodyElement is null)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "envelope is missing required fields");
        }

        if (versionElement.Value.ValueKind != JsonValueKind.Number
            || !versionElement.Value.TryGetInt64(out var version))
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "envelope version must be an integer");
        }

        if (version != ProtocolLimits.ProtocolVersionV2)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.UnsupportedVersion, "protocol version is unsupported");
        }

        if (typeElement.Value.ValueKind != JsonValueKind.String
            || typeElement.Value.GetString() is not { } type
            || !BodyTypes.TryGetValue(type, out var bodyType))
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "message type is unknown");
        }

        if (requestIdElement.Value.ValueKind != JsonValueKind.String
            || requestIdElement.Value.GetString() is not { } requestIdText
            || !ProtocolValidation.IsCanonicalUuid(requestIdText)
            || !Guid.TryParseExact(requestIdText, "D", out var requestId)
            || requestId == Guid.Empty)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "request_id is not a canonical non-nil UUID");
        }

        if (bodyElement.Value.ValueKind != JsonValueKind.Object)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "body must be an object");
        }

        object? body;
        try
        {
            body = bodyElement.Value.Deserialize(bodyType, ProtocolReader.BodyOptions);
        }
        catch (JsonException)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, $"body shape is invalid for {type}");
        }
        catch (NotSupportedException)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, $"body shape is invalid for {type}");
        }

        if (body is null)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, "body cannot be null");
        }

        var semanticError = ProtocolValidation.ValidateV2(type, body);
        if (semanticError is not null)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, semanticError);
        }

        return new ProtocolParseOutcome.Success((int)version, type, requestId, body);
    }
}
