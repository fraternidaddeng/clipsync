using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using ClipSync.Core.Protocol;

namespace ClipSync.Peer.Pairing;

/// <summary>
/// Strict reader/writer for pairing documents: same token-level rules as the WebSocket
/// envelope (no duplicates, no nulls, depth 16, unknown fields rejected) plus the semantic
/// checks from pairing.schema.json, so both sides fail identically on bad input.
/// </summary>
public static class PairingJson
{
    public const int MaxDocumentBytes = 8 * 1024;

    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        NumberHandling = JsonNumberHandling.Strict,
        MaxDepth = ProtocolLimits.MaxJsonDepth,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public static string Serialize(object document)
    {
        ArgumentNullException.ThrowIfNull(document);
        return JsonSerializer.Serialize(document, document.GetType(), Options);
    }

    public static PairingQrPayload? ParseQrPayload(string text, out string? error) =>
        Parse<PairingQrPayload>(text, PairingDocumentKinds.Qr, ValidateQr, out error);

    public static PairingConfirmRequest? ParseConfirmRequest(ReadOnlySpan<byte> utf8, out string? error) =>
        Parse<PairingConfirmRequest>(utf8, PairingDocumentKinds.ConfirmRequest, ValidateRequest, out error);

    public static PairingConfirmResponse? ParseConfirmResponse(string text, out string? error) =>
        Parse<PairingConfirmResponse>(text, PairingDocumentKinds.ConfirmResponse, ValidateResponse, out error);

    public static PairingErrorBody? ParseError(string text, out string? error) =>
        Parse<PairingErrorBody>(text, PairingDocumentKinds.Error, ValidateError, out error);

    private static T? Parse<T>(string text, string expectedKind, Func<T, string?> validate, out string? error)
        where T : class
    {
        ArgumentNullException.ThrowIfNull(text);
        return Parse(Encoding.UTF8.GetBytes(text), expectedKind, validate, out error);
    }

    private static T? Parse<T>(ReadOnlySpan<byte> utf8, string expectedKind, Func<T, string?> validate, out string? error)
        where T : class
    {
        if (utf8.Length > MaxDocumentBytes)
        {
            error = "document exceeds the pairing size limit";
            return null;
        }

        error = ProtocolReader.ScanStrictJson(utf8);
        if (error is not null)
        {
            return null;
        }

        T? document;
        try
        {
            document = JsonSerializer.Deserialize<T>(utf8, Options);
        }
        catch (JsonException)
        {
            error = "document shape is invalid";
            return null;
        }

        if (document is null)
        {
            error = "document cannot be null";
            return null;
        }

        var kind = (string?)typeof(T).GetProperty("Kind")?.GetValue(document);
        if (!string.Equals(kind, expectedKind, StringComparison.Ordinal))
        {
            error = "kind discriminator does not match";
            return null;
        }

        error = validate(document);
        return error is null ? document : null;
    }

    private static string? ValidateCommon(int version, string deviceId, string displayName)
    {
        if (version != ProtocolLimits.ProtocolVersion)
        {
            return "version is unsupported";
        }

        if (!ProtocolValidation.IsCanonicalUuid(deviceId))
        {
            return "device_id is not a canonical UUID";
        }

        var trimmed = displayName.Trim();
        if (trimmed.Length is < 1 or > 64)
        {
            return "display_name must be 1..64 characters";
        }

        return null;
    }

    private static string? ValidateQr(PairingQrPayload qr)
    {
        if (ValidateCommon(qr.Version, qr.DeviceId, qr.DisplayName) is { } common)
        {
            return common;
        }

        if (qr.Hosts.Count is < 1 or > 8
            || qr.Hosts.Any(host => host.Length is < 1 or > 253)
            || qr.Hosts.Distinct(StringComparer.Ordinal).Count() != qr.Hosts.Count)
        {
            return "hosts must be 1..8 unique entries";
        }

        if (qr.Port is < 1 or > 65535)
        {
            return "port is out of range";
        }

        if (!ProtocolValidation.IsLowercaseSha256(qr.CertSha256))
        {
            return "cert_sha256 is not lowercase SHA-256 hex";
        }

        if (!ProtocolValidation.TryDecodeBase64Url256(qr.Token, out _))
        {
            return "token is not 32 bytes of unpadded base64url";
        }

        return qr.ExpiresAtMs < 1 ? "expires_at_ms must be positive" : null;
    }

    private static string? ValidateRequest(PairingConfirmRequest request)
    {
        if (ValidateCommon(request.Version, request.DeviceId, request.DisplayName) is { } common)
        {
            return common;
        }

        if (!ProtocolValidation.TryDecodeBase64Url256(request.Token, out _))
        {
            return "token is not 32 bytes of unpadded base64url";
        }

        return IsKnownPlatform(request.Platform) ? null : "platform is unsupported";
    }

    private static string? ValidateResponse(PairingConfirmResponse response)
    {
        if (ValidateCommon(response.Version, response.DeviceId, response.DisplayName) is { } common)
        {
            return common;
        }

        if (!IsKnownPlatform(response.Platform))
        {
            return "platform is unsupported";
        }

        if (!ProtocolValidation.TryDecodeBase64Url256(response.PairSecret, out _))
        {
            return "pair_secret is not 32 bytes of unpadded base64url";
        }

        return response.TrustEpoch < 1 ? "trust_epoch must be at least 1" : null;
    }

    private static string? ValidateError(PairingErrorBody body)
    {
        if (body.Version != ProtocolLimits.ProtocolVersion)
        {
            return "version is unsupported";
        }

        return body.Error is PairingErrorCodes.SchemaViolation
            or PairingErrorCodes.TokenInvalid
            or PairingErrorCodes.TokenExpired
            or PairingErrorCodes.Rejected
            or PairingErrorCodes.Timeout
            ? null
            : "error code is unknown";
    }

    private static bool IsKnownPlatform(string platform) =>
        platform is "windows" or "android";
}
