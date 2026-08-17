using System.Security.Cryptography;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Peer.Diagnostics;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipSync.Peer.Pairing;

public sealed record PairingServiceOptions
{
    public required string LocalDisplayName { get; init; }

    public string LocalPlatform { get; init; } = "windows";

    /// <summary>Protocol section 9: the token expires within five minutes.</summary>
    public TimeSpan TicketLifetime { get; init; } = TimeSpan.FromMinutes(5);

    /// <summary>How long a confirm request waits for the user's explicit approval.</summary>
    public TimeSpan ApprovalTimeout { get; init; } = TimeSpan.FromSeconds(90);

    public TimeProvider TimeProvider { get; init; } = TimeProvider.System;
}

/// <summary>
/// Owns the one-time pairing token and the confirm flow: token issue/cancel/consume with
/// constant-time comparison, explicit user approval, pair secret generation, and the
/// devices-table write. Tokens and secrets never reach a log line.
/// </summary>
public sealed class PairingService
{
    private readonly SqliteClipboardEventStore store;
    private readonly ISecretProtector secretProtector;
    private readonly IPairingApprover approver;
    private readonly PairingServiceOptions options;
    private readonly ILogger logger;
    private readonly TimeProvider clock;
    private readonly object gate = new();
    private ActiveTicket? ticket;

    public PairingService(
        SqliteClipboardEventStore store,
        ISecretProtector secretProtector,
        IPairingApprover approver,
        PairingServiceOptions options,
        ILogger? logger = null)
    {
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.secretProtector = secretProtector ?? throw new ArgumentNullException(nameof(secretProtector));
        this.approver = approver ?? throw new ArgumentNullException(nameof(approver));
        this.options = options ?? throw new ArgumentNullException(nameof(options));
        this.logger = logger ?? NullLogger.Instance;
        clock = options.TimeProvider;
    }

    /// <summary>Raised after a pairing (or re-pairing) commits, for UI refresh.</summary>
    public event Action<PairedDevice>? PairingCompleted;

    /// <summary>Issues a fresh one-time token; any previous ticket is invalidated.</summary>
    public PairingTicket IssueTicket()
    {
        var tokenBytes = RandomNumberGenerator.GetBytes(32);
        var expiresAt = clock.GetUtcNow().Add(options.TicketLifetime);
        lock (gate)
        {
            ticket = new ActiveTicket(tokenBytes, expiresAt);
        }

        PeerLog.PairingTicketIssued(logger, expiresAt.ToUnixTimeMilliseconds());
        return new PairingTicket(ProtocolValidation.EncodeBase64Url(tokenBytes), expiresAt);
    }

    /// <summary>Invalidates the outstanding token, e.g. when the QR window closes.</summary>
    public void CancelTicket()
    {
        lock (gate)
        {
            ticket = null;
        }
    }

    public PairingQrPayload BuildQrPayload(
        PairingTicket pairingTicket,
        IReadOnlyList<string> hosts,
        int port,
        string certificateFingerprint)
    {
        ArgumentNullException.ThrowIfNull(pairingTicket);
        ArgumentNullException.ThrowIfNull(hosts);
        if (hosts.Count is < 1 or > 8)
        {
            throw new ArgumentException("The QR payload carries 1..8 host candidates.", nameof(hosts));
        }

        return new PairingQrPayload
        {
            Kind = PairingDocumentKinds.Qr,
            Version = ProtocolLimits.ProtocolVersion,
            Hosts = hosts,
            Port = port,
            DeviceId = store.LocalDeviceId,
            DisplayName = options.LocalDisplayName,
            CertSha256 = certificateFingerprint,
            Token = pairingTicket.Token,
            ExpiresAtMs = pairingTicket.ExpiresAt.ToUnixTimeMilliseconds()
        };
    }

    public async Task<PairingConfirmOutcome> ConfirmAsync(
        PairingConfirmRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (string.Equals(request.DeviceId, store.LocalDeviceId, StringComparison.Ordinal))
        {
            return new PairingConfirmOutcome.Failed(400, PairingErrorCodes.SchemaViolation);
        }

        var consumeFailure = ConsumeToken(request.Token);
        if (consumeFailure is not null)
        {
            PeerLog.PairingConfirmFailed(logger, consumeFailure.ErrorCode);
            return consumeFailure;
        }

        var existing = await store.GetDeviceAsync(request.DeviceId, cancellationToken).ConfigureAwait(false);
        var candidate = new PairingCandidate(
            request.DeviceId,
            request.DisplayName.Trim(),
            request.Platform,
            IsRepair: existing is not null);

        bool approved;
        using var timeoutSource = new CancellationTokenSource(options.ApprovalTimeout, clock);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeoutSource.Token);
        try
        {
            approved = await approver.ApproveAsync(candidate, linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (timeoutSource.IsCancellationRequested)
        {
            PeerLog.PairingConfirmFailed(logger, PairingErrorCodes.Timeout);
            return new PairingConfirmOutcome.Failed(403, PairingErrorCodes.Timeout);
        }

        if (!approved)
        {
            PeerLog.PairingConfirmFailed(logger, PairingErrorCodes.Rejected);
            return new PairingConfirmOutcome.Failed(403, PairingErrorCodes.Rejected);
        }

        var secret = RandomNumberGenerator.GetBytes(PairAuthProof.SecretLength);
        var protectedSecret = Convert.ToBase64String(secretProtector.Protect(secret));
        var device = await store.UpsertDeviceAsync(
            new NewPairedDevice(
                candidate.DeviceId,
                candidate.DisplayName,
                candidate.Platform,
                CertificateFingerprint: string.Empty,
                protectedSecret),
            clock.GetUtcNow(),
            cancellationToken).ConfigureAwait(false);

        var response = new PairingConfirmResponse
        {
            Kind = PairingDocumentKinds.ConfirmResponse,
            Version = ProtocolLimits.ProtocolVersion,
            DeviceId = store.LocalDeviceId,
            DisplayName = options.LocalDisplayName,
            Platform = options.LocalPlatform,
            PairSecret = ProtocolValidation.EncodeBase64Url(secret),
            TrustEpoch = device.TrustEpoch
        };
        CryptographicOperations.ZeroMemory(secret);

        PeerLog.PairingConfirmed(logger, device.DeviceId, candidate.IsRepair, device.TrustEpoch);
        PairingCompleted?.Invoke(device);
        return new PairingConfirmOutcome.Approved(response, device);
    }

    /// <summary>
    /// Single-use consumption: a matching token is burned no matter what happens afterwards.
    /// A non-matching guess does not burn the outstanding ticket (the token has 256 bits of
    /// entropy, so guessing is hopeless anyway), and comparison is constant-time.
    /// </summary>
    private PairingConfirmOutcome.Failed? ConsumeToken(string presentedToken)
    {
        if (!ProtocolValidation.TryDecodeBase64Url256(presentedToken, out var presentedBytes))
        {
            return new PairingConfirmOutcome.Failed(400, PairingErrorCodes.SchemaViolation);
        }

        lock (gate)
        {
            if (ticket is null)
            {
                return new PairingConfirmOutcome.Failed(403, PairingErrorCodes.TokenInvalid);
            }

            if (clock.GetUtcNow() > ticket.ExpiresAt)
            {
                ticket = null;
                return new PairingConfirmOutcome.Failed(410, PairingErrorCodes.TokenExpired);
            }

            if (!CryptographicOperations.FixedTimeEquals(ticket.TokenBytes, presentedBytes))
            {
                return new PairingConfirmOutcome.Failed(403, PairingErrorCodes.TokenInvalid);
            }

            ticket = null;
            return null;
        }
    }

    private sealed record ActiveTicket(byte[] TokenBytes, DateTimeOffset ExpiresAt);
}
