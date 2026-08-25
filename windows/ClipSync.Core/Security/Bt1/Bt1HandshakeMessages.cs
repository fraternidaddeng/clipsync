using System.Text;
using System.Text.Json;
using ClipSync.Core.Protocol;

namespace ClipSync.Core.Security.Bt1;

/// <summary>
/// Stable bt1 error codes from docs/protocol-bt1.md section 6. The strings must stay
/// byte-identical to protocol/bt1/handshake.schema.json.
/// </summary>
public static class Bt1ErrorCodes
{
    public const string SchemaViolation = "BT1_SCHEMA_VIOLATION";
    public const string VersionUnsupported = "BT1_VERSION_UNSUPPORTED";
    public const string AuthFailed = "BT1_AUTH_FAILED";
    public const string RateLimited = "BT1_RATE_LIMITED";
    public const string FrameTooLarge = "BT1_FRAME_TOO_LARGE";

    /// <summary>Local diagnostics only; never valid on the wire.</summary>
    public const string DecryptFailed = "BT1_DECRYPT_FAILED";

    public static readonly IReadOnlySet<string> WireCodes = new HashSet<string>(StringComparer.Ordinal)
    {
        SchemaViolation,
        VersionUnsupported,
        AuthFailed,
        RateLimited,
        FrameTooLarge
    };
}

/// <summary>One parsed bt1 handshake message (docs/protocol-bt1.md section 3).</summary>
public abstract record Bt1HandshakeMessage
{
    private Bt1HandshakeMessage()
    {
    }

    public sealed record Hello(Bt1Role SenderRole, Guid DeviceId, long TrustEpoch, ReadOnlyMemory<byte> Nonce) : Bt1HandshakeMessage;

    public sealed record Auth(Bt1Role SenderRole, ReadOnlyMemory<byte> Proof) : Bt1HandshakeMessage;

    public sealed record ChannelError(string Code) : Bt1HandshakeMessage;
}

public abstract record Bt1HandshakeParseOutcome
{
    private Bt1HandshakeParseOutcome()
    {
    }

    public sealed record Success(Bt1HandshakeMessage Message) : Bt1HandshakeParseOutcome;

    /// <summary>Rejection with a stable bt1 error code; <see cref="Reason"/> is diagnostic only.</summary>
    public sealed record Failure(string ErrorCode, string Reason) : Bt1HandshakeParseOutcome;
}

/// <summary>
/// Strict codec for the five bt1 handshake messages. Parsing enforces the protocol v1
/// section 2 JSON rules (strict UTF-8, duplicate properties rejected, no null, no unknown
/// fields) plus the bt1 shapes in protocol/bt1/handshake.schema.json; the shared message
/// fixtures live in protocol/bt1/fixtures/handshake/valid and invalid.
/// </summary>
public static class Bt1HandshakeCodec
{
    private const string ClientHelloKind = "bt1_client_hello";
    private const string ListenerHelloKind = "bt1_listener_hello";
    private const string ClientAuthKind = "bt1_client_auth";
    private const string ListenerAuthKind = "bt1_listener_auth";
    private const string ErrorKind = "bt1_error";
    private const int ChannelVersion = 1;

    public static Bt1HandshakeParseOutcome Parse(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        return Parse(Encoding.UTF8.GetBytes(text));
    }

    public static Bt1HandshakeParseOutcome Parse(ReadOnlySpan<byte> utf8)
    {
        if (utf8.Length is < Bt1Frames.MinHandshakePayloadLength or > Bt1Frames.MaxHandshakePayloadLength)
        {
            return new Bt1HandshakeParseOutcome.Failure(
                utf8.Length > Bt1Frames.MaxHandshakePayloadLength ? Bt1ErrorCodes.FrameTooLarge : Bt1ErrorCodes.SchemaViolation,
                "handshake payload length is outside 2..4096 bytes");
        }

        if (ProtocolReader.ScanStrictJson(utf8) is { } scanReason)
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, scanReason);
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(utf8.ToArray(), new JsonDocumentOptions { MaxDepth = ProtocolLimits.MaxJsonDepth });
        }
        catch (JsonException)
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "document parse failed");
        }

        using (document)
        {
            return ParseMessage(document.RootElement);
        }
    }

    private static Bt1HandshakeParseOutcome ParseMessage(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object)
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "handshake message must be an object");
        }

        var fields = new Dictionary<string, JsonElement>(StringComparer.Ordinal);
        foreach (var property in root.EnumerateObject())
        {
            fields[property.Name] = property.Value;
        }

        if (!fields.TryGetValue("kind", out var kindElement)
            || kindElement.ValueKind != JsonValueKind.String
            || kindElement.GetString() is not { } kind)
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "kind is missing or not a string");
        }

        var isKnownKind = kind is ClientHelloKind or ListenerHelloKind or ClientAuthKind or ListenerAuthKind or ErrorKind;
        if (!isKnownKind)
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "kind is unknown");
        }

        if (!fields.TryGetValue("version", out var versionElement)
            || versionElement.ValueKind != JsonValueKind.Number
            || !versionElement.TryGetInt64(out var version))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "version is missing or not an integer");
        }

        if (version != ChannelVersion)
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.VersionUnsupported, "bt1 channel version is unsupported");
        }

        return kind switch
        {
            ClientHelloKind => ParseHello(fields, Bt1Role.Client),
            ListenerHelloKind => ParseHello(fields, Bt1Role.Listener),
            ClientAuthKind => ParseAuth(fields, Bt1Role.Client),
            ListenerAuthKind => ParseAuth(fields, Bt1Role.Listener),
            _ => ParseError(fields)
        };
    }

    private static Bt1HandshakeParseOutcome ParseHello(Dictionary<string, JsonElement> fields, Bt1Role senderRole)
    {
        if (fields.Count != 5
            || !fields.TryGetValue("device_id", out var deviceIdElement)
            || !fields.TryGetValue("trust_epoch", out var epochElement)
            || !fields.TryGetValue("nonce", out var nonceElement))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "hello must carry exactly kind, version, device_id, trust_epoch, nonce");
        }

        if (deviceIdElement.ValueKind != JsonValueKind.String
            || deviceIdElement.GetString() is not { } deviceIdText
            || !ProtocolValidation.IsCanonicalUuid(deviceIdText)
            || !Guid.TryParseExact(deviceIdText, "D", out var deviceId))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "device_id is not a canonical UUID");
        }

        if (epochElement.ValueKind != JsonValueKind.Number
            || !epochElement.TryGetInt64(out var trustEpoch)
            || trustEpoch < 1)
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "trust_epoch must be a positive 64-bit integer");
        }

        if (nonceElement.ValueKind != JsonValueKind.String
            || !ProtocolValidation.TryDecodeBase64Url256(nonceElement.GetString(), out var nonce))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "nonce is not canonical base64url for 32 bytes");
        }

        return new Bt1HandshakeParseOutcome.Success(new Bt1HandshakeMessage.Hello(senderRole, deviceId, trustEpoch, nonce));
    }

    private static Bt1HandshakeParseOutcome ParseAuth(Dictionary<string, JsonElement> fields, Bt1Role senderRole)
    {
        if (fields.Count != 3 || !fields.TryGetValue("proof", out var proofElement))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "auth must carry exactly kind, version, proof");
        }

        if (proofElement.ValueKind != JsonValueKind.String
            || !ProtocolValidation.TryDecodeBase64Url256(proofElement.GetString(), out var proof))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "proof is not canonical base64url for 32 bytes");
        }

        return new Bt1HandshakeParseOutcome.Success(new Bt1HandshakeMessage.Auth(senderRole, proof));
    }

    private static Bt1HandshakeParseOutcome ParseError(Dictionary<string, JsonElement> fields)
    {
        if (fields.Count != 3 || !fields.TryGetValue("code", out var codeElement))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "error must carry exactly kind, version, code");
        }

        if (codeElement.ValueKind != JsonValueKind.String
            || codeElement.GetString() is not { } code
            || !Bt1ErrorCodes.WireCodes.Contains(code))
        {
            return new Bt1HandshakeParseOutcome.Failure(Bt1ErrorCodes.SchemaViolation, "error code is unknown");
        }

        return new Bt1HandshakeParseOutcome.Success(new Bt1HandshakeMessage.ChannelError(code));
    }

    public static string SerializeHello(Bt1Role senderRole, Guid deviceId, long trustEpoch, ReadOnlySpan<byte> nonce)
    {
        if (trustEpoch < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(trustEpoch), "trust_epoch must be at least 1.");
        }

        if (nonce.Length != Bt1AuthProof.NonceLength)
        {
            throw new ArgumentException("The nonce must be exactly 32 bytes.", nameof(nonce));
        }

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("kind", senderRole == Bt1Role.Client ? ClientHelloKind : ListenerHelloKind);
            writer.WriteNumber("version", ChannelVersion);
            writer.WriteString("device_id", deviceId.ToString("D"));
            writer.WriteNumber("trust_epoch", trustEpoch);
            writer.WriteString("nonce", ProtocolValidation.EncodeBase64Url(nonce));
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string SerializeAuth(Bt1Role senderRole, ReadOnlySpan<byte> proof)
    {
        if (proof.Length != Bt1AuthProof.ProofLength)
        {
            throw new ArgumentException("The proof must be exactly 32 bytes.", nameof(proof));
        }

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("kind", senderRole == Bt1Role.Client ? ClientAuthKind : ListenerAuthKind);
            writer.WriteNumber("version", ChannelVersion);
            writer.WriteString("proof", ProtocolValidation.EncodeBase64Url(proof));
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string SerializeError(string code)
    {
        ArgumentNullException.ThrowIfNull(code);
        if (!Bt1ErrorCodes.WireCodes.Contains(code))
        {
            throw new ArgumentException("Only wire-legal bt1 error codes may be serialized.", nameof(code));
        }

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("kind", ErrorKind);
            writer.WriteNumber("version", ChannelVersion);
            writer.WriteString("code", code);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }
}
