using System.Text.Json.Serialization;
using ClipSync.Core.Storage;

namespace ClipSync.Peer.Pairing;

/// <summary>Discriminator values for the pairing documents frozen in pairing.schema.json.</summary>
public static class PairingDocumentKinds
{
    public const string Qr = "pairing_qr";
    public const string ConfirmRequest = "pairing_confirm_request";
    public const string ConfirmResponse = "pairing_confirm_response";
    public const string Error = "pairing_error";
}

public static class PairingErrorCodes
{
    public const string SchemaViolation = "SCHEMA_VIOLATION";
    public const string TokenInvalid = "PAIRING_TOKEN_INVALID";
    public const string TokenExpired = "PAIRING_TOKEN_EXPIRED";
    public const string Rejected = "PAIRING_REJECTED";
    public const string Timeout = "PAIRING_TIMEOUT";
}

/// <summary>The JSON object rendered inside the pairing QR code. Never contains the pair secret.</summary>
public sealed record PairingQrPayload
{
    [JsonPropertyName("kind")]
    public required string Kind { get; init; }

    [JsonPropertyName("version")]
    public required int Version { get; init; }

    [JsonPropertyName("hosts")]
    public required IReadOnlyList<string> Hosts { get; init; }

    [JsonPropertyName("port")]
    public required int Port { get; init; }

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("display_name")]
    public required string DisplayName { get; init; }

    [JsonPropertyName("cert_sha256")]
    public required string CertSha256 { get; init; }

    [JsonPropertyName("token")]
    public required string Token { get; init; }

    [JsonPropertyName("expires_at_ms")]
    public required long ExpiresAtMs { get; init; }
}

public sealed record PairingConfirmRequest
{
    [JsonPropertyName("kind")]
    public required string Kind { get; init; }

    [JsonPropertyName("version")]
    public required int Version { get; init; }

    [JsonPropertyName("token")]
    public required string Token { get; init; }

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("display_name")]
    public required string DisplayName { get; init; }

    [JsonPropertyName("platform")]
    public required string Platform { get; init; }
}

public sealed record PairingConfirmResponse
{
    [JsonPropertyName("kind")]
    public required string Kind { get; init; }

    [JsonPropertyName("version")]
    public required int Version { get; init; }

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("display_name")]
    public required string DisplayName { get; init; }

    [JsonPropertyName("platform")]
    public required string Platform { get; init; }

    [JsonPropertyName("pair_secret")]
    public required string PairSecret { get; init; }

    [JsonPropertyName("trust_epoch")]
    public required long TrustEpoch { get; init; }
}

public sealed record PairingErrorBody
{
    [JsonPropertyName("kind")]
    public required string Kind { get; init; }

    [JsonPropertyName("version")]
    public required int Version { get; init; }

    [JsonPropertyName("error")]
    public required string Error { get; init; }
}

/// <summary>A one-time pairing token handed to the QR renderer.</summary>
public sealed record PairingTicket(string Token, DateTimeOffset ExpiresAt);

/// <summary>What the approval UI shows before a pairing is saved.</summary>
public sealed record PairingCandidate(string DeviceId, string DisplayName, string Platform, bool IsRepair);

/// <summary>
/// Bridges the confirm endpoint to the user. Implementations show the candidate's name and
/// platform and return the explicit decision; cancellation means the approval window elapsed.
/// </summary>
public interface IPairingApprover
{
    Task<bool> ApproveAsync(PairingCandidate candidate, CancellationToken cancellationToken);
}

public abstract record PairingConfirmOutcome
{
    private PairingConfirmOutcome()
    {
    }

    public sealed record Approved(PairingConfirmResponse Response, PairedDevice Device) : PairingConfirmOutcome;

    public sealed record Failed(int HttpStatus, string ErrorCode) : PairingConfirmOutcome;
}
