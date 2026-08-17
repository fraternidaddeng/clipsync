using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ClipSync.Core.Protocol;

public abstract record ProtocolParseOutcome
{
    private ProtocolParseOutcome()
    {
    }

    public sealed record Success(int Version, string Type, Guid RequestId, object Body) : ProtocolParseOutcome;

    /// <summary>Rejection with a stable protocol error code; <see cref="Reason"/> is diagnostic only and never contains message content.</summary>
    public sealed record Failure(string ErrorCode, string Reason) : ProtocolParseOutcome;
}

/// <summary>
/// Strict protocol v1 frame reader: token-level duplicate-property, depth, and null checks,
/// exact envelope shape, per-type body deserialization, and the mandatory semantic rules.
/// </summary>
public static class ProtocolReader
{
    internal static readonly JsonSerializerOptions BodyOptions = new()
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        NumberHandling = JsonNumberHandling.Strict,
        MaxDepth = ProtocolLimits.MaxJsonDepth,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

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
        var scanFailure = ScanTokens(utf8);
        if (scanFailure is not null)
        {
            return scanFailure;
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

        if (version != ProtocolLimits.ProtocolVersion)
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
            body = bodyElement.Value.Deserialize(bodyType, BodyOptions);
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

        var semanticError = ProtocolValidation.Validate(type, body);
        if (semanticError is not null)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.SchemaViolation, semanticError);
        }

        return new ProtocolParseOutcome.Success((int)version, type, requestId, body);
    }

    /// <summary>
    /// Runs only the token-level strictness pass (malformed JSON, depth, duplicate properties,
    /// null values) for non-envelope documents such as pairing bodies. Null means clean.
    /// </summary>
    public static string? ScanStrictJson(ReadOnlySpan<byte> utf8) => ScanTokens(utf8)?.Reason;

    /// <summary>Token-level pass rejecting malformed JSON, nesting above 16, duplicate properties, and null values.</summary>
    private static ProtocolParseOutcome.Failure? ScanTokens(ReadOnlySpan<byte> utf8)
    {
        var reader = new Utf8JsonReader(
            utf8,
            new JsonReaderOptions
            {
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
                MaxDepth = ProtocolLimits.MaxJsonDepth
            });

        var propertyNamesByDepth = new Stack<HashSet<string>>();
        try
        {
            while (reader.Read())
            {
                switch (reader.TokenType)
                {
                    case JsonTokenType.StartObject:
                        propertyNamesByDepth.Push(new HashSet<string>(StringComparer.Ordinal));
                        break;
                    case JsonTokenType.EndObject:
                        propertyNamesByDepth.Pop();
                        break;
                    case JsonTokenType.PropertyName:
                        if (!propertyNamesByDepth.Peek().Add(reader.GetString()!))
                        {
                            return new ProtocolParseOutcome.Failure(
                                ProtocolErrorCodes.MalformedJson,
                                "duplicate object property");
                        }

                        break;
                    case JsonTokenType.Null:
                        return new ProtocolParseOutcome.Failure(
                            ProtocolErrorCodes.SchemaViolation,
                            "null values are not allowed in protocol v1");
                }
            }
        }
        catch (JsonException)
        {
            return new ProtocolParseOutcome.Failure(ProtocolErrorCodes.MalformedJson, "malformed JSON frame");
        }

        return null;
    }
}

public static class ProtocolWriter
{
    public static string Serialize(string type, Guid requestId, object body)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(type);
        ArgumentNullException.ThrowIfNull(body);
        if (requestId == Guid.Empty)
        {
            throw new ArgumentException("A sender generates a fresh non-nil request ID.", nameof(requestId));
        }

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("version", ProtocolLimits.ProtocolVersion);
            writer.WriteString("type", type);
            writer.WriteString("request_id", requestId.ToString("D"));
            writer.WritePropertyName("body");
            JsonSerializer.Serialize(writer, body, body.GetType(), ProtocolReader.BodyOptions);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }
}
