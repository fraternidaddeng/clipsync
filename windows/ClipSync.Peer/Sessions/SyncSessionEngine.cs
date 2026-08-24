using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Core.Sync;
using ClipSync.Peer.Diagnostics;
using ClipSync.Peer.Transport;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipSync.Peer.Sessions;

/// <summary>
/// One protocol v1 sync session over one transport. The same engine runs on both ends:
/// the role only decides who issues the challenge. All storage effects go through
/// <see cref="SqliteClipboardEventStore"/> and are transactional there.
/// Log lines never contain clipboard content, nonces, proofs, or secrets.
/// </summary>
public sealed class SyncSessionEngine : IDisposable
{
    private const string HmacAlgorithm = ProtocolValidation.HmacSha256;

    private readonly SyncSessionRole role;
    private readonly SqliteClipboardEventStore store;
    private readonly ISecretProtector secretProtector;
    private readonly SyncSessionOptions options;
    private readonly IAuthFailureSink? authFailures;
    private readonly ILogger logger;
    private readonly TimeProvider clock;
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly Dictionary<Guid, ClipHeaderDto> outstandingFetches = [];
    private readonly Dictionary<Guid, IncomingImageTransfer> incomingImages = [];
    private readonly SemaphoreSlim imageDownloadSlots = new(MediaLimits.MaxConcurrentDownloads, MediaLimits.MaxConcurrentDownloads);
    private readonly ReplayWindow replayWindow = new(capacity: 512);
    private readonly object completionLock = new();
    private int protocolVersion;

    private ISyncTransport transport = null!;
    private CancellationTokenSource sessionCts = null!;
    private SessionState state;
    private PairedDevice? peerDevice;
    private byte[]? pairSecret;
    private OutstandingChallenge? challenge;
    private Dictionary<string, OriginReceiveState> peerVector = new(StringComparer.Ordinal);
    private bool wantBacklogPending;
    private bool peerConfirmed;
    private int unansweredPings;
    private string? lastPeerErrorCode;
    private SyncSessionResult? completion;

    public SyncSessionEngine(
        SyncSessionRole role,
        SqliteClipboardEventStore store,
        ISecretProtector secretProtector,
        SyncSessionOptions options,
        IAuthFailureSink? authFailureSink = null,
        ILogger? logger = null)
    {
        this.role = role;
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.secretProtector = secretProtector ?? throw new ArgumentNullException(nameof(secretProtector));
        this.options = options ?? throw new ArgumentNullException(nameof(options));
        authFailures = authFailureSink;
        this.logger = logger ?? NullLogger.Instance;
        clock = options.TimeProvider;
        protocolVersion = options.ProtocolVersion is 1 or 2 ? options.ProtocolVersion : ProtocolLimits.ProtocolVersion;

        if (role == SyncSessionRole.Dialer && string.IsNullOrWhiteSpace(options.ExpectedPeerDeviceId))
        {
            throw new ArgumentException("A dialer session needs ExpectedPeerDeviceId.", nameof(options));
        }
    }

    /// <summary>Raised after a batch of remote clip bodies committed locally, in commit order.</summary>
    public event Action<IReadOnlyList<RemoteClipApplied>>? RemoteClipsCommitted;

    /// <summary>
    /// Raised with the peer device id once the session reaches the ready state (on the
    /// listener this is right after the proof verified). Raised on a worker thread.
    /// </summary>
    public event Action<string>? SessionReady;

    /// <summary>The authenticated peer, available once the handshake completed.</summary>
    public string? PeerDeviceId => peerDevice?.DeviceId;

    /// <summary>True once the handshake finished and data messages may flow.</summary>
    public bool IsReady => state == SessionState.Ready;

    /// <summary>
    /// True only when both directions confirmed the handshake: the listener verified the proof,
    /// or the dialer saw the listener continue past auth with a data message.
    /// </summary>
    private bool IsAuthenticated => state == SessionState.Ready && peerConfirmed;

    /// <summary>
    /// Both-ends opt-in for image bodies (protocol v2 §3): a v2 session and the local
    /// image_sync gate. Re-evaluated per image so a settings flip applies mid-session.
    /// </summary>
    private bool ImageClipAllowed => protocolVersion == ProtocolLimits.ProtocolVersionV2 && options.ImageSyncEnabled();

    /// <summary>Asks the session to stop; used on revocation and shutdown.</summary>
    public void RequestClose()
    {
        try
        {
            sessionCts?.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
    }

    public async Task<SyncSessionResult> RunAsync(ISyncTransport sessionTransport, CancellationToken cancellationToken)
    {
        transport = sessionTransport ?? throw new ArgumentNullException(nameof(sessionTransport));
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        sessionCts = cts;
        var token = cts.Token;

        using var handshakeTimer = clock.CreateTimer(
            _ =>
            {
                if (state != SessionState.Ready)
                {
                    Complete(new SyncSessionResult(false, null, "handshake_timeout"));
                    RequestClose();
                }
            },
            state: null,
            options.HandshakeTimeout,
            Timeout.InfiniteTimeSpan);

        Task? pingLoop = null;
        Task? drainLoop = null;
        try
        {
            if (role == SyncSessionRole.Dialer)
            {
                if (!await StartAsDialerAsync(token).ConfigureAwait(false))
                {
                    return completion ?? new SyncSessionResult(false, null, "dialer_prerequisites_missing");
                }
            }
            else
            {
                state = SessionState.ExpectHello;
            }

            while (!token.IsCancellationRequested)
            {
                var frame = await transport.ReceiveAsync(token).ConfigureAwait(false);
                if (frame is TransportFrame.Closed)
                {
                    // When the peer closed before we authenticated, its last reported error
                    // (for example a retryable RATE_LIMITED) is the best available reason.
                    Complete(new SyncSessionResult(IsAuthenticated, IsAuthenticated ? null : lastPeerErrorCode, "peer_closed"));
                    break;
                }

                if (frame is TransportFrame.Binary)
                {
                    await FailAsync(ProtocolErrorCodes.SchemaViolation, "binary_frame", token).ConfigureAwait(false);
                    break;
                }

                if (frame is TransportFrame.TooLarge)
                {
                    await FailAsync(ProtocolErrorCodes.PayloadTooLarge, "frame_over_limit", token).ConfigureAwait(false);
                    break;
                }

                var text = ((TransportFrame.Text)frame).Payload;
                if (!await DispatchAsync(text, token).ConfigureAwait(false))
                {
                    break;
                }

                if (state == SessionState.Ready && pingLoop is null)
                {
                    pingLoop = RunPingLoopAsync(token);
                    drainLoop = RunOutboxLoopAsync(token);
                }
            }
        }
        catch (OperationCanceledException)
        {
            Complete(new SyncSessionResult(IsAuthenticated, null, "cancelled"));
        }
        finally
        {
            cts.Cancel();
            await AwaitQuietlyAsync(pingLoop).ConfigureAwait(false);
            await AwaitQuietlyAsync(drainLoop).ConfigureAwait(false);
            await transport.CloseAsync(WebSocketCloseStatus.NormalClosure, "session_end", CancellationToken.None).ConfigureAwait(false);
            await DrainUntilPeerClosesAsync().ConfigureAwait(false);
            if (pairSecret is not null)
            {
                CryptographicOperations.ZeroMemory(pairSecret);
            }
        }

        var result = completion ?? new SyncSessionResult(IsAuthenticated, null, "closed");
        PeerLog.SessionEnded(logger, role.ToString(), PeerDeviceId ?? "unknown", result.Authenticated, result.ErrorCode ?? "none", result.Detail);
        return result;
    }

    private async Task<bool> StartAsDialerAsync(CancellationToken token)
    {
        var device = await store.GetDeviceAsync(options.ExpectedPeerDeviceId!, token).ConfigureAwait(false);
        if (device is null || device.IsRevoked || !TryResolveSecret(device, out var secret))
        {
            Complete(new SyncSessionResult(false, ProtocolErrorCodes.AuthFailed, "peer_not_pairable"));
            return false;
        }

        peerDevice = device;
        pairSecret = secret;
        state = SessionState.ExpectChallenge;
        await SendAsync(ProtocolMessageTypes.Hello, new HelloBody
        {
            DeviceId = store.LocalDeviceId,
            Platform = options.Platform,
            ClientVersion = options.ClientVersion,
            TrustEpoch = device.TrustEpoch,
            KnownVector = await BuildKnownVectorAsync(token).ConfigureAwait(false),
            Capabilities = protocolVersion == ProtocolLimits.ProtocolVersionV2
                ? [ProtocolLimits.CapabilityImageClipV2]
                : null
        }, token).ConfigureAwait(false);
        return true;
    }

    /// <summary>Returns false when the session must stop.</summary>
    private async Task<bool> DispatchAsync(string text, CancellationToken token)
    {
        var outcome = protocolVersion == ProtocolLimits.ProtocolVersionV2
            ? ProtocolReaderV2.Parse(text)
            : ProtocolReader.Parse(text);
        if (outcome is ProtocolParseOutcome.Failure failure)
        {
            PeerLog.FrameRejected(logger, failure.ErrorCode, failure.Reason);
            await FailAsync(failure.ErrorCode, "invalid_frame", token).ConfigureAwait(false);
            return false;
        }

        var message = (ProtocolParseOutcome.Success)outcome;
        var replay = replayWindow.Classify(message.RequestId, text);
        if (replay == ReplayVerdict.IdenticalRetry)
        {
            return true;
        }

        if (replay == ReplayVerdict.Conflict)
        {
            await FailAsync(ProtocolErrorCodes.ReplayDetected, "request_id_reuse", token).ConfigureAwait(false);
            return false;
        }

        PeerLog.MessageReceived(logger, message.Type);
        switch (message.Type)
        {
            case ProtocolMessageTypes.Ping:
                await SendAsync(ProtocolMessageTypes.Pong, new PongBody
                {
                    PingSentAtMs = ((PingBody)message.Body).SentAtMs,
                    SentAtMs = clock.GetUtcNow().ToUnixTimeMilliseconds()
                }, token).ConfigureAwait(false);
                return true;
            case ProtocolMessageTypes.Pong:
                Interlocked.Exchange(ref unansweredPings, 0);
                return true;
            case ProtocolMessageTypes.Error:
                return await HandleErrorAsync((ErrorBody)message.Body, token).ConfigureAwait(false);
            case ProtocolMessageTypes.Hello:
                return await HandleHelloAsync((HelloBody)message.Body, token).ConfigureAwait(false);
            case ProtocolMessageTypes.Challenge:
                return await HandleChallengeAsync((ChallengeBody)message.Body, message.RequestId, token).ConfigureAwait(false);
            case ProtocolMessageTypes.Auth:
                return await HandleAuthAsync((AuthBody)message.Body, token).ConfigureAwait(false);
        }

        if (state != SessionState.Ready)
        {
            await FailAsync(ProtocolErrorCodes.AuthRequired, "data_before_auth", token).ConfigureAwait(false);
            return false;
        }

        // A data message from the listener is the dialer's confirmation that auth passed.
        peerConfirmed = true;

        if (!await EnsurePeerStillTrustedAsync(token).ConfigureAwait(false))
        {
            return false;
        }

        return message.Type switch
        {
            ProtocolMessageTypes.KnownVector => await HandleKnownVectorAsync((SyncStateDto)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.WantRanges => await HandleWantRangesAsync((WantRangesBody)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.ClipAnnounce => await HandleClipAnnounceAsync((ClipAnnounceBody)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.ClipFetch => await HandleClipFetchAsync((ClipFetchBody)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.ClipPayload => await HandleClipPayloadAsync((ClipPayloadBody)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.ClipPayloadBegin => await HandleClipPayloadBeginAsync((ClipPayloadBeginBody)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.ClipPayloadChunk => await HandleClipPayloadChunkAsync((ClipPayloadChunkBody)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.ClipPayloadEnd => await HandleClipPayloadEndAsync((ClipPayloadEndBody)message.Body, token).ConfigureAwait(false),
            ProtocolMessageTypes.AckRanges => await HandleAckRangesAsync((AckRangesBody)message.Body, token).ConfigureAwait(false),
            _ => await UnexpectedAsync(token).ConfigureAwait(false)
        };
    }

    private async Task<bool> UnexpectedAsync(CancellationToken token)
    {
        await FailAsync(ProtocolErrorCodes.MessageOutOfOrder, "unexpected_type", token).ConfigureAwait(false);
        return false;
    }

    private async Task<bool> HandleErrorAsync(ErrorBody error, CancellationToken token)
    {
        PeerLog.PeerError(logger, error.Code, error.Retryable, error.FailedType ?? "none");
        lastPeerErrorCode = error.Code;
        if (error.Retryable)
        {
            return true;
        }

        Complete(new SyncSessionResult(IsAuthenticated, error.Code, "peer_reported_fatal_error"));
        await transport.CloseAsync(WebSocketCloseStatus.NormalClosure, "peer_error", token).ConfigureAwait(false);
        return false;
    }

    private async Task<bool> HandleHelloAsync(HelloBody hello, CancellationToken token)
    {
        if (role != SyncSessionRole.Listener || state != SessionState.ExpectHello)
        {
            return await UnexpectedAsync(token).ConfigureAwait(false);
        }

        if (hello.DeviceId == store.LocalDeviceId)
        {
            await FailAsync(ProtocolErrorCodes.AuthFailed, "self_connection", token).ConfigureAwait(false);
            return false;
        }

        var device = await store.GetDeviceAsync(hello.DeviceId, token).ConfigureAwait(false);
        if (device is null)
        {
            authFailures?.RecordAuthFailure(hello.DeviceId);
            await FailAsync(ProtocolErrorCodes.AuthFailed, "unknown_device", token).ConfigureAwait(false);
            return false;
        }

        if (device.IsRevoked)
        {
            await FailAsync(ProtocolErrorCodes.DeviceRevoked, "device_revoked", token).ConfigureAwait(false);
            return false;
        }

        if (authFailures?.IsThrottled(device.DeviceId) == true)
        {
            await SendAsync(ProtocolMessageTypes.Error, new ErrorBody
            {
                Code = ProtocolErrorCodes.RateLimited,
                Retryable = true,
                RetryAfterMs = 30_000
            }, token).ConfigureAwait(false);
            Complete(new SyncSessionResult(false, ProtocolErrorCodes.RateLimited, "auth_throttled"));
            await transport.CloseAsync(WebSocketCloseStatus.PolicyViolation, "throttled", token).ConfigureAwait(false);
            return false;
        }

        if (hello.TrustEpoch != device.TrustEpoch)
        {
            await FailAsync(ProtocolErrorCodes.TrustEpochMismatch, "hello_epoch_mismatch", token).ConfigureAwait(false);
            return false;
        }

        if (!TryResolveSecret(device, out var secret))
        {
            authFailures?.RecordAuthFailure(device.DeviceId);
            await FailAsync(ProtocolErrorCodes.AuthFailed, "secret_unavailable", token).ConfigureAwait(false);
            return false;
        }

        peerDevice = device;
        pairSecret = secret;

        var nonce = RandomNumberGenerator.GetBytes(PairAuthProof.NonceLength);
        var challengeRequestId = Guid.NewGuid();
        var expiresAt = clock.GetUtcNow().Add(options.ChallengeLifetime);
        challenge = new OutstandingChallenge(challengeRequestId, nonce, device.TrustEpoch, expiresAt);
        state = SessionState.ExpectAuth;
        await SendWithRequestIdAsync(ProtocolMessageTypes.Challenge, challengeRequestId, new ChallengeBody
        {
            Algorithm = HmacAlgorithm,
            Nonce = ProtocolValidation.EncodeBase64Url(nonce),
            ChallengerDeviceId = store.LocalDeviceId,
            ResponderDeviceId = device.DeviceId,
            TrustEpoch = device.TrustEpoch,
            ExpiresAtMs = expiresAt.ToUnixTimeMilliseconds()
        }, token).ConfigureAwait(false);
        return true;
    }

    private async Task<bool> HandleChallengeAsync(ChallengeBody body, Guid requestId, CancellationToken token)
    {
        if (role != SyncSessionRole.Dialer || state != SessionState.ExpectChallenge || peerDevice is null || pairSecret is null)
        {
            return await UnexpectedAsync(token).ConfigureAwait(false);
        }

        if (body.ChallengerDeviceId != peerDevice.DeviceId || body.ResponderDeviceId != store.LocalDeviceId)
        {
            await FailAsync(ProtocolErrorCodes.AuthFailed, "challenge_identity_mismatch", token).ConfigureAwait(false);
            return false;
        }

        if (body.TrustEpoch != peerDevice.TrustEpoch)
        {
            await FailAsync(ProtocolErrorCodes.TrustEpochMismatch, "challenge_epoch_mismatch", token).ConfigureAwait(false);
            return false;
        }

        if (body.ExpiresAtMs <= clock.GetUtcNow().ToUnixTimeMilliseconds())
        {
            await FailAsync(ProtocolErrorCodes.ChallengeExpired, "challenge_already_expired", token).ConfigureAwait(false);
            return false;
        }

        ProtocolValidation.TryDecodeBase64Url256(body.Nonce, out var nonce);
        var proof = PairAuthProof.Compute(
            pairSecret,
            requestId,
            nonce,
            Guid.Parse(peerDevice.DeviceId),
            Guid.Parse(store.LocalDeviceId),
            body.TrustEpoch,
            protocolVersion);

        await SendAsync(ProtocolMessageTypes.Auth, new AuthBody
        {
            Algorithm = HmacAlgorithm,
            ChallengeRequestId = requestId.ToString("D"),
            ResponderDeviceId = store.LocalDeviceId,
            TrustEpoch = body.TrustEpoch,
            Proof = ProtocolValidation.EncodeBase64Url(proof)
        }, token).ConfigureAwait(false);

        // The listener's next data message implies acceptance; a failure arrives as error/close.
        await EnterReadyAsync(token).ConfigureAwait(false);
        return true;
    }

    private async Task<bool> HandleAuthAsync(AuthBody auth, CancellationToken token)
    {
        if (role != SyncSessionRole.Listener || state != SessionState.ExpectAuth
            || peerDevice is null || pairSecret is null || challenge is null)
        {
            return await UnexpectedAsync(token).ConfigureAwait(false);
        }

        var outstanding = challenge;
        challenge = null; // single use, success or not

        if (clock.GetUtcNow() > outstanding.ExpiresAt)
        {
            await FailAsync(ProtocolErrorCodes.ChallengeExpired, "challenge_expired", token).ConfigureAwait(false);
            return false;
        }

        if (auth.ChallengeRequestId != outstanding.RequestId.ToString("D")
            || auth.ResponderDeviceId != peerDevice.DeviceId)
        {
            authFailures?.RecordAuthFailure(peerDevice.DeviceId);
            await FailAsync(ProtocolErrorCodes.AuthFailed, "auth_binding_mismatch", token).ConfigureAwait(false);
            return false;
        }

        if (auth.TrustEpoch != outstanding.TrustEpoch)
        {
            await FailAsync(ProtocolErrorCodes.TrustEpochMismatch, "auth_epoch_mismatch", token).ConfigureAwait(false);
            return false;
        }

        ProtocolValidation.TryDecodeBase64Url256(auth.Proof, out var proof);
        var valid = PairAuthProof.Verify(
            pairSecret,
            outstanding.RequestId,
            outstanding.Nonce,
            Guid.Parse(store.LocalDeviceId),
            Guid.Parse(peerDevice.DeviceId),
            outstanding.TrustEpoch,
            proof,
            protocolVersion);
        if (!valid)
        {
            authFailures?.RecordAuthFailure(peerDevice.DeviceId);
            await FailAsync(ProtocolErrorCodes.AuthFailed, "proof_mismatch", token).ConfigureAwait(false);
            return false;
        }

        await store.UpdateDeviceLastSeenAsync(peerDevice.DeviceId, clock.GetUtcNow(), token).ConfigureAwait(false);
        peerConfirmed = true;
        await EnterReadyAsync(token).ConfigureAwait(false);
        return true;
    }

    private async Task EnterReadyAsync(CancellationToken token)
    {
        state = SessionState.Ready;
        await store.ResetOutboxToPendingAsync(peerDevice!.DeviceId, token).ConfigureAwait(false);
        await ApplyPersistedPeerAcksAsync(token).ConfigureAwait(false);
        await SendAsync(ProtocolMessageTypes.KnownVector, await BuildKnownVectorAsync(token).ConfigureAwait(false), token).ConfigureAwait(false);
        PeerLog.SessionReady(logger, role.ToString(), peerDevice.DeviceId);
        SessionReady?.Invoke(peerDevice.DeviceId);
    }

    private async Task ApplyPersistedPeerAcksAsync(CancellationToken token)
    {
        var cursors = await store.GetPeerCursorsAsync(peerDevice!.DeviceId, token).ConfigureAwait(false);
        var covered = cursors
            .Where(entry => entry.Key != peerDevice.DeviceId)
            .Select(entry => new OriginSequenceRanges(entry.Key, entry.Value.ToCoverage()))
            .Where(entry => entry.Ranges.Count > 0)
            .ToArray();
        if (covered.Length > 0)
        {
            await store.ApplyPeerAckRangesAsync(
                    peerDevice.DeviceId,
                    covered,
                    clock.GetUtcNow(),
                    dropTerminalOutbox: false,
                    token)
                .ConfigureAwait(false);
        }
    }

    private async Task<bool> HandleKnownVectorAsync(SyncStateDto vector, CancellationToken token)
    {
        var parsed = new Dictionary<string, OriginReceiveState>(StringComparer.Ordinal);
        try
        {
            foreach (var origin in vector.Origins)
            {
                parsed[origin.OriginDeviceId] = new OriginReceiveState(
                    origin.ContiguousSeq,
                    origin.ReceivedRanges is null
                        ? []
                        : origin.ReceivedRanges.Select(range => new SequenceRange(range.StartSeq, range.EndSeq)).ToArray());
            }
        }
        catch (ArgumentException)
        {
            await FailAsync(ProtocolErrorCodes.SchemaViolation, "vector_invariants", token).ConfigureAwait(false);
            return false;
        }

        peerVector = parsed;
        if (parsed.TryGetValue(store.LocalDeviceId, out var ours))
        {
            var high = ours.HighestCoveredSeq();
            if (high >= 1)
            {
                await store.AdoptPeerCoverageOfLocalOriginAsync(high, token).ConfigureAwait(false);
            }
        }

        // Their persisted coverage is acknowledgment evidence: prune what they already hold.
        var covered = parsed
            .Where(entry => entry.Key != peerDevice!.DeviceId)
            .Select(entry => new OriginSequenceRanges(entry.Key, entry.Value.ToCoverage()))
            .Where(entry => entry.Ranges.Count > 0)
            .ToArray();
        if (covered.Length > 0)
        {
            await store.ApplyPeerAckRangesAsync(
                    peerDevice!.DeviceId,
                    covered,
                    clock.GetUtcNow(),
                    dropTerminalOutbox: false,
                    token)
                .ConfigureAwait(false);
        }

        await SendWantsAsync(token).ConfigureAwait(false);
        return true;
    }

    private async Task SendWantsAsync(CancellationToken token)
    {
        var mine = await store.GetKnownVectorAsync(token).ConfigureAwait(false);
        var requests = new List<OriginRangesDto>();
        var capped = false;
        foreach (var (origin, theirs) in peerVector)
        {
            if (origin == store.LocalDeviceId)
            {
                continue;
            }

            if (origin != peerDevice!.DeviceId && !await IsTrustedOriginAsync(origin, token).ConfigureAwait(false))
            {
                continue;
            }

            var local = mine.TryGetValue(origin, out var found) ? found : OriginReceiveState.Empty;
            var missing = local.MissingFrom(theirs);
            if (missing.Count == 0)
            {
                continue;
            }

            var limited = SequenceRangeMath.Take(missing, options.WantSequencesPerOrigin);
            capped |= SequenceRangeMath.TotalCount(limited) < SequenceRangeMath.TotalCount(missing);
            requests.Add(new OriginRangesDto
            {
                OriginDeviceId = origin,
                Ranges = limited.Select(range => new RangeDto { StartSeq = range.StartSeq, EndSeq = range.EndSeq }).ToArray()
            });
        }

        wantBacklogPending = capped;
        if (requests.Count > 0)
        {
            await SendAsync(ProtocolMessageTypes.WantRanges, new WantRangesBody { Requests = requests }, token).ConfigureAwait(false);
        }
    }

    private async Task<bool> HandleWantRangesAsync(WantRangesBody wants, CancellationToken token)
    {
        var totalRequested = wants.Requests.Sum(request =>
            SequenceRangeMath.TotalCount(request.Ranges.Select(range => new SequenceRange(range.StartSeq, range.EndSeq)).ToArray()));
        if (totalRequested > options.MaxRequestedSequencesPerMessage)
        {
            await SendAsync(ProtocolMessageTypes.Error, new ErrorBody
            {
                Code = ProtocolErrorCodes.RateLimited,
                Retryable = true,
                FailedType = ProtocolMessageTypes.WantRanges,
                RetryAfterMs = 1_000
            }, token).ConfigureAwait(false);
            return true;
        }

        if (!options.OutboundAllowed())
        {
            // Paused/private: the peer's pull is not served. It re-requests on the next
            // vector exchange, and the outbox drain announces backlog once the gate reopens.
            return true;
        }

        foreach (var request in wants.Requests)
        {
            var remaining = request.Ranges
                .Select(range => new SequenceRange(range.StartSeq, range.EndSeq))
                .ToArray() as IReadOnlyList<SequenceRange>;
            while (remaining.Count > 0)
            {
                var events = await store.GetSyncableEventsAsync(
                    request.OriginDeviceId,
                    remaining,
                    ProtocolLimits.MaxAnnounceClips,
                    token).ConfigureAwait(false);
                if (events.Count == 0)
                {
                    break;
                }

                var headers = new List<ClipHeaderDto>(events.Count);
                foreach (var item in events)
                {
                    headers.Add(BuildHeader(item));
                }

                await SendAsync(ProtocolMessageTypes.ClipAnnounce, new ClipAnnounceBody
                {
                    Clips = headers.ToArray()
                }, token).ConfigureAwait(false);

                if (events.Count < ProtocolLimits.MaxAnnounceClips)
                {
                    break;
                }

                var served = SequenceRangeMath.Normalize(
                    events.Select(item => new SequenceRange(item.OriginSeq, item.OriginSeq)));
                remaining = SequenceRangeMath.Subtract(remaining, served);
            }
        }

        return true;
    }

    private async Task<bool> HandleClipAnnounceAsync(ClipAnnounceBody announce, CancellationToken token)
    {
        var mine = await store.GetKnownVectorAsync(token).ConfigureAwait(false);
        var acks = new List<(string Origin, long Seq)>();
        var committed = new List<RemoteClipApplied>();
        var fetchIds = new List<string>();

        foreach (var header in announce.Clips)
        {
            var origin = header.OriginDeviceId;
            if (origin == store.LocalDeviceId)
            {
                continue;
            }

            if (origin != peerDevice!.DeviceId && !await IsTrustedOriginAsync(origin, token).ConfigureAwait(false))
            {
                PeerLog.UntrustedOriginSkipped(logger, origin);
                continue;
            }

            var localState = mine.TryGetValue(origin, out var found) ? found : OriginReceiveState.Empty;
            if (localState.Contains(header.OriginSeq) && header.Availability != ClipAvailability.Unavailable)
            {
                acks.Add((origin, header.OriginSeq));
                continue;
            }

            if (header.Availability == ClipAvailability.Unavailable)
            {
                var marker = new RemoteTerminalMarker(
                    Guid.Parse(header.EventId),
                    origin,
                    header.OriginSeq,
                    header.Reason!);
                var stored = await store.StoreRemoteTerminalAsync(marker, peerDevice.DeviceId, clock.GetUtcNow(), token).ConfigureAwait(false);
                if (stored is RemoteStoreResult.IdentityConflict conflict)
                {
                    PeerLog.StoreConflict(logger, "terminal", conflict.Detail);
                    await FailAsync(ProtocolErrorCodes.EventConflict, "terminal_conflict", token).ConfigureAwait(false);
                    return false;
                }

                acks.Add((origin, header.OriginSeq));
                continue;
            }

            if (string.Equals(header.Kind, "image", StringComparison.Ordinal))
            {
                if (protocolVersion != ProtocolLimits.ProtocolVersionV2)
                {
                    await FailAsync(ProtocolErrorCodes.UnsupportedMedia, "image_on_v1", token).ConfigureAwait(false);
                    return false;
                }

                if (!options.ImageSyncEnabled())
                {
                    // The route gate keeps new v2 sessions from opening while image sync is
                    // off, but a live session can still see an image when the toggle flips
                    // mid-session: refuse instead of committing content the user opted out
                    // of. The peer's redial falls back to /v1 and text sync continues.
                    await FailAsync(ProtocolErrorCodes.UnsupportedMedia, "image_sync_disabled", token).ConfigureAwait(false);
                    return false;
                }

                if (await store.FindLiveBlobByHashAsync(header.ContentHash!, token).ConfigureAwait(false))
                {
                    var replayImage = new RemoteClipEvent(
                        Guid.Parse(header.EventId),
                        origin,
                        header.OriginSeq,
                        Content: null,
                        header.ContentHash!,
                        header.SourceApp,
                        DateTimeOffset.FromUnixTimeMilliseconds(header.CreatedAtMs!.Value),
                        header.ExpiresAtMs is null ? null : DateTimeOffset.FromUnixTimeMilliseconds(header.ExpiresAtMs.Value),
                        Kind: "image",
                        MimeType: header.MimeType,
                        EncodedBytes: header.EncodedBytes is null ? null : (int)header.EncodedBytes.Value,
                        PixelWidth: header.PixelWidth is null ? null : (int)header.PixelWidth.Value,
                        PixelHeight: header.PixelHeight is null ? null : (int)header.PixelHeight.Value);
                    var storedImage = await store.StoreRemoteEventAsync(replayImage, peerDevice.DeviceId, token).ConfigureAwait(false);
                    if (storedImage is RemoteStoreResult.IdentityConflict imageConflict)
                    {
                        PeerLog.StoreConflict(logger, "announce", imageConflict.Detail);
                        await FailAsync(ProtocolErrorCodes.EventConflict, "announce_conflict", token).ConfigureAwait(false);
                        return false;
                    }

                    acks.Add((origin, header.OriginSeq));
                    if (storedImage is RemoteStoreResult.Stored)
                    {
                        committed.Add(new RemoteClipApplied(
                            replayImage.EventId,
                            origin,
                            header.OriginSeq,
                            string.Empty,
                            replayImage.CreatedAt,
                            "image",
                            replayImage.ContentHash,
                            replayImage.MimeType));
                    }

                    continue;
                }

                if (outstandingFetches.TryAdd(Guid.Parse(header.EventId), header))
                {
                    fetchIds.Add(header.EventId);
                }

                continue;
            }

            var known = await store.FindLiveContentByHashAsync(header.ContentHash!, token).ConfigureAwait(false);
            if (known is not null && Encoding.UTF8.GetByteCount(known) == header.Utf8Bytes)
            {
                var replayEvent = new RemoteClipEvent(
                    Guid.Parse(header.EventId),
                    origin,
                    header.OriginSeq,
                    known,
                    header.ContentHash!,
                    header.SourceApp,
                    DateTimeOffset.FromUnixTimeMilliseconds(header.CreatedAtMs!.Value),
                    header.ExpiresAtMs is null ? null : DateTimeOffset.FromUnixTimeMilliseconds(header.ExpiresAtMs.Value));
                var stored = await store.StoreRemoteEventAsync(replayEvent, peerDevice.DeviceId, token).ConfigureAwait(false);
                if (stored is RemoteStoreResult.IdentityConflict conflict)
                {
                    PeerLog.StoreConflict(logger, "announce", conflict.Detail);
                    await FailAsync(ProtocolErrorCodes.EventConflict, "announce_conflict", token).ConfigureAwait(false);
                    return false;
                }

                acks.Add((origin, header.OriginSeq));
                if (stored is RemoteStoreResult.Stored)
                {
                    committed.Add(new RemoteClipApplied(
                        replayEvent.EventId,
                        origin,
                        header.OriginSeq,
                        known,
                        replayEvent.CreatedAt));
                }

                continue;
            }

            if (outstandingFetches.TryAdd(Guid.Parse(header.EventId), header))
            {
                fetchIds.Add(header.EventId);
            }
        }

        foreach (var chunk in fetchIds.Chunk(ProtocolLimits.MaxFetchEventIds))
        {
            await SendAsync(ProtocolMessageTypes.ClipFetch, new ClipFetchBody { EventIds = chunk }, token).ConfigureAwait(false);
        }

        await SendAcksAsync(acks, token).ConfigureAwait(false);
        RaiseCommitted(committed);
        return true;
    }

    private async Task<bool> HandleClipFetchAsync(ClipFetchBody fetch, CancellationToken token)
    {
        var ids = fetch.EventIds.Select(Guid.Parse).ToArray();
        var events = await store.GetSyncableEventsByIdsAsync(ids, token).ConfigureAwait(false);
        var byId = events.ToDictionary(item => item.EventId);

        var payloadItems = new List<ClipPayloadItemDto>();
        var terminalHeaders = new List<ClipHeaderDto>();
        var missing = 0;
        foreach (var id in ids)
        {
            if (!byId.TryGetValue(id, out var item))
            {
                missing++;
                continue;
            }

            if (item.IsTerminal)
            {
                terminalHeaders.Add(BuildHeader(item));
                continue;
            }

            if (item.IsImage)
            {
                if (!ImageClipAllowed)
                {
                    terminalHeaders.Add(BuildHeader(item));
                    continue;
                }

                if (!await SendImagePayloadAsync(item, token).ConfigureAwait(false))
                {
                    return false;
                }

                continue;
            }

            payloadItems.Add(new ClipPayloadItemDto
            {
                EventId = item.EventId.ToString("D"),
                OriginDeviceId = item.OriginDeviceId,
                OriginSeq = item.OriginSeq,
                Kind = "text",
                Content = item.Content!,
                ContentHash = item.ContentHash!,
                Utf8Bytes = Encoding.UTF8.GetByteCount(item.Content!),
                SourceApp = item.SourceApp,
                CreatedAtMs = item.CreatedAt.ToUnixTimeMilliseconds(),
                ExpiresAtMs = item.ExpiresAt?.ToUnixTimeMilliseconds()
            });
        }

        foreach (var chunk in terminalHeaders.Chunk(ProtocolLimits.MaxAnnounceClips))
        {
            await SendAsync(ProtocolMessageTypes.ClipAnnounce, new ClipAnnounceBody { Clips = chunk }, token).ConfigureAwait(false);
        }

        foreach (var batch in ChunkPayloads(payloadItems))
        {
            await SendAsync(ProtocolMessageTypes.ClipPayload, new ClipPayloadBody { Clips = batch }, token).ConfigureAwait(false);
        }

        if (missing > 0)
        {
            PeerLog.FetchMissingIds(logger, missing);
            await SendAsync(ProtocolMessageTypes.Error, new ErrorBody
            {
                Code = ProtocolErrorCodes.PayloadNotFound,
                Retryable = true,
                FailedType = ProtocolMessageTypes.ClipFetch
            }, token).ConfigureAwait(false);
        }

        return true;
    }

    private async Task<bool> HandleClipPayloadAsync(ClipPayloadBody payload, CancellationToken token)
    {
        var acks = new List<(string Origin, long Seq)>();
        var committed = new List<RemoteClipApplied>();

        foreach (var item in payload.Clips)
        {
            var eventId = Guid.Parse(item.EventId);
            if (!outstandingFetches.Remove(eventId, out var header))
            {
                await FailAsync(ProtocolErrorCodes.MessageOutOfOrder, "payload_without_fetch", token).ConfigureAwait(false);
                return false;
            }

            if (item.OriginDeviceId != header.OriginDeviceId
                || item.OriginSeq != header.OriginSeq
                || item.ContentHash != header.ContentHash
                || item.Utf8Bytes != header.Utf8Bytes)
            {
                await FailAsync(ProtocolErrorCodes.EventConflict, "payload_header_mismatch", token).ConfigureAwait(false);
                return false;
            }

            var remoteEvent = new RemoteClipEvent(
                eventId,
                item.OriginDeviceId,
                item.OriginSeq,
                item.Content,
                item.ContentHash,
                item.SourceApp,
                DateTimeOffset.FromUnixTimeMilliseconds(item.CreatedAtMs),
                item.ExpiresAtMs is null ? null : DateTimeOffset.FromUnixTimeMilliseconds(item.ExpiresAtMs.Value));
            var stored = await store.StoreRemoteEventAsync(remoteEvent, peerDevice!.DeviceId, token).ConfigureAwait(false);
            if (stored is RemoteStoreResult.IdentityConflict conflict)
            {
                PeerLog.StoreConflict(logger, "payload", conflict.Detail);
                await FailAsync(ProtocolErrorCodes.EventConflict, "payload_conflict", token).ConfigureAwait(false);
                return false;
            }

            acks.Add((item.OriginDeviceId, item.OriginSeq));
            if (stored is RemoteStoreResult.Stored)
            {
                committed.Add(new RemoteClipApplied(
                    eventId,
                    item.OriginDeviceId,
                    item.OriginSeq,
                    item.Content,
                    remoteEvent.CreatedAt));
            }
        }

        await SendAcksAsync(acks, token).ConfigureAwait(false);
        RaiseCommitted(committed);

        if (wantBacklogPending)
        {
            await SendWantsAsync(token).ConfigureAwait(false);
        }

        return true;
    }

    private async Task<bool> HandleAckRangesAsync(AckRangesBody body, CancellationToken token)
    {
        var ranges = body.Acks
            .Select(ack => new OriginSequenceRanges(
                ack.OriginDeviceId,
                ack.Ranges.Select(range => new SequenceRange(range.StartSeq, range.EndSeq)).ToArray()))
            .ToArray();
        await store.ApplyPeerAckRangesAsync(peerDevice!.DeviceId, ranges, clock.GetUtcNow(), token).ConfigureAwait(false);
        return true;
    }

    private async Task RunPingLoopAsync(CancellationToken token)
    {
        try
        {
            using var timer = new PeriodicTimer(options.PingInterval, clock);
            while (await timer.WaitForNextTickAsync(token).ConfigureAwait(false))
            {
                if (Interlocked.Increment(ref unansweredPings) > options.MaxMissedPings)
                {
                    Complete(new SyncSessionResult(true, null, "ping_timeout"));
                    RequestClose();
                    return;
                }

                await SendAsync(ProtocolMessageTypes.Ping, new PingBody
                {
                    SentAtMs = clock.GetUtcNow().ToUnixTimeMilliseconds()
                }, token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception exception)
        {
            PeerLog.BackgroundLoopStopped(logger, "ping", exception.GetType().Name);
            Complete(new SyncSessionResult(true, null, "send_failed"));
            RequestClose();
        }
    }

    private async Task RunOutboxLoopAsync(CancellationToken token)
    {
        try
        {
            await DrainOutboxAsync(token).ConfigureAwait(false);
            using var timer = new PeriodicTimer(options.OutboxDrainInterval, clock);
            while (await timer.WaitForNextTickAsync(token).ConfigureAwait(false))
            {
                await DrainOutboxAsync(token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception exception)
        {
            PeerLog.BackgroundLoopStopped(logger, "outbox", exception.GetType().Name);
            Complete(new SyncSessionResult(true, null, "send_failed"));
            RequestClose();
        }
    }

    private async Task DrainOutboxAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            if (!options.OutboundAllowed())
            {
                // Entries stay pending (never marked announced), so nothing is lost: the
                // next drain tick after unpausing announces them.
                return;
            }

            var batch = await store.GetOutboxBatchAsync(peerDevice!.DeviceId, ProtocolLimits.MaxAnnounceClips, token).ConfigureAwait(false);
            if (batch.Count == 0)
            {
                return;
            }

            var headers = new List<ClipHeaderDto>(batch.Count);
            var dropped = new List<long>();
            foreach (var row in batch)
            {
                var origin = row.Event.OriginDeviceId;
                if (origin != store.LocalDeviceId && origin != peerDevice!.DeviceId)
                {
                    dropped.Add(row.Entry.Id);
                    continue;
                }

                headers.Add(BuildHeader(row.Event));
            }

            if (dropped.Count > 0)
            {
                await store.DeleteOutboxIdsAsync(dropped, token).ConfigureAwait(false);
            }

            if (headers.Count == 0)
            {
                if (dropped.Count == 0)
                {
                    return;
                }

                continue;
            }

            await SendAsync(ProtocolMessageTypes.ClipAnnounce, new ClipAnnounceBody
            {
                Clips = headers.ToArray()
            }, token).ConfigureAwait(false);
            await store.MarkOutboxAnnouncedAsync(
                batch.Where(row => !dropped.Contains(row.Entry.Id)).Select(row => row.Entry.Id).ToArray(),
                token).ConfigureAwait(false);

            if (batch.Count < ProtocolLimits.MaxAnnounceClips)
            {
                return;
            }
        }
    }

    private async Task<bool> EnsurePeerStillTrustedAsync(CancellationToken token)
    {
        var device = await store.GetDeviceAsync(peerDevice!.DeviceId, token).ConfigureAwait(false);
        if (device is null || device.IsRevoked || device.TrustEpoch != peerDevice.TrustEpoch)
        {
            await FailAsync(ProtocolErrorCodes.DeviceRevoked, "peer_no_longer_trusted", token).ConfigureAwait(false);
            return false;
        }

        return true;
    }

    private async ValueTask<bool> IsTrustedOriginAsync(string originDeviceId, CancellationToken token)
    {
        var device = await store.GetDeviceAsync(originDeviceId, token).ConfigureAwait(false);
        return device is not null && !device.IsRevoked;
    }

    private async Task SendAcksAsync(IReadOnlyList<(string Origin, long Seq)> acks, CancellationToken token)
    {
        if (acks.Count == 0)
        {
            return;
        }

        var grouped = acks
            .GroupBy(item => item.Origin, StringComparer.Ordinal)
            .Select(group => new OriginRangesDto
            {
                OriginDeviceId = group.Key,
                Ranges = SequenceRangeMath.Normalize(group.Select(item => new SequenceRange(item.Seq, item.Seq)))
                    .Select(range => new RangeDto { StartSeq = range.StartSeq, EndSeq = range.EndSeq })
                    .ToArray()
            })
            .ToArray();
        await SendAsync(ProtocolMessageTypes.AckRanges, new AckRangesBody { Acks = grouped }, token).ConfigureAwait(false);
    }

    private void RaiseCommitted(IReadOnlyList<RemoteClipApplied> committed)
    {
        if (committed.Count > 0)
        {
            RemoteClipsCommitted?.Invoke(committed);
        }
    }

    private async ValueTask<SyncStateDto> BuildKnownVectorAsync(CancellationToken token)
    {
        var vector = await store.GetKnownVectorAsync(token).ConfigureAwait(false);
        return new SyncStateDto
        {
            Origins = vector
                .OrderBy(entry => entry.Key, StringComparer.Ordinal)
                .Select(entry => new OriginStateDto
                {
                    OriginDeviceId = entry.Key,
                    ContiguousSeq = entry.Value.ContiguousSeq,
                    ReceivedRanges = entry.Value.ReceivedRanges.Count == 0
                        ? null
                        : entry.Value.ReceivedRanges
                            .Select(range => new RangeDto { StartSeq = range.StartSeq, EndSeq = range.EndSeq })
                            .ToArray()
                })
                .ToArray()
        };
    }

    private ClipHeaderDto BuildHeader(SyncableClipEvent item)
    {
        // Images are downgraded to a local_only marker on v1 sessions and while the local
        // image_sync gate is off, so the origin cursor still advances without image bodies.
        if (item.IsTerminal || (item.IsImage && !ImageClipAllowed))
        {
            var reason = item.IsImage && !ImageClipAllowed
                ? ClipUnavailableReasons.LocalOnly
                : item.TerminalReason;

            return new ClipHeaderDto
            {
                EventId = item.EventId.ToString("D"),
                OriginDeviceId = item.OriginDeviceId,
                OriginSeq = item.OriginSeq,
                Availability = ClipAvailability.Unavailable,
                Reason = reason
            };
        }

        if (item.IsImage)
        {
            return new ClipHeaderDto
            {
                EventId = item.EventId.ToString("D"),
                OriginDeviceId = item.OriginDeviceId,
                OriginSeq = item.OriginSeq,
                Availability = ClipAvailability.Available,
                Kind = "image",
                ContentHash = item.ContentHash,
                MimeType = item.MimeType,
                EncodedBytes = item.EncodedBytes,
                PixelWidth = item.PixelWidth,
                PixelHeight = item.PixelHeight,
                SourceApp = item.SourceApp,
                CreatedAtMs = item.CreatedAt.ToUnixTimeMilliseconds(),
                ExpiresAtMs = item.ExpiresAt?.ToUnixTimeMilliseconds()
            };
        }

        return new ClipHeaderDto
        {
            EventId = item.EventId.ToString("D"),
            OriginDeviceId = item.OriginDeviceId,
            OriginSeq = item.OriginSeq,
            Availability = ClipAvailability.Available,
            Kind = "text",
            ContentHash = item.ContentHash,
            Utf8Bytes = Encoding.UTF8.GetByteCount(item.Content!),
            SourceApp = item.SourceApp,
            CreatedAtMs = item.CreatedAt.ToUnixTimeMilliseconds(),
            ExpiresAtMs = item.ExpiresAt?.ToUnixTimeMilliseconds()
        };
    }

    private async Task<bool> SendImagePayloadAsync(SyncableClipEvent item, CancellationToken token)
    {
        byte[] bytes;
        try
        {
            bytes = store.Media.ReadAllBytes(item.ContentHash!);
        }
        catch (Exception exception) when (exception is FileNotFoundException or InvalidDataException)
        {
            await FailAsync(ProtocolErrorCodes.MediaStorageFailed, "blob_missing", token).ConfigureAwait(false);
            return false;
        }

        var chunks = ImageChunks.Split(bytes);
        var transferId = Guid.NewGuid().ToString("D");
        await SendAsync(ProtocolMessageTypes.ClipPayloadBegin, new ClipPayloadBeginBody
        {
            TransferId = transferId,
            EventId = item.EventId.ToString("D"),
            ChunkCount = chunks.Count,
            EncodedBytes = bytes.Length,
            ContentHash = item.ContentHash!,
            MimeType = item.MimeType ?? MediaLimits.MimePng
        }, token).ConfigureAwait(false);

        foreach (var chunk in chunks)
        {
            await SendAsync(ProtocolMessageTypes.ClipPayloadChunk, new ClipPayloadChunkBody
            {
                TransferId = transferId,
                EventId = item.EventId.ToString("D"),
                ChunkIndex = chunk.Index,
                ChunkCount = chunk.Count,
                ChunkBytes = chunk.ByteCount,
                Data = chunk.Data
            }, token).ConfigureAwait(false);
        }

        await SendAsync(ProtocolMessageTypes.ClipPayloadEnd, new ClipPayloadEndBody
        {
            TransferId = transferId,
            EventId = item.EventId.ToString("D"),
            ContentHash = item.ContentHash!
        }, token).ConfigureAwait(false);
        return true;
    }

    private async Task<bool> HandleClipPayloadBeginAsync(ClipPayloadBeginBody begin, CancellationToken token)
    {
        if (protocolVersion != ProtocolLimits.ProtocolVersionV2)
        {
            await FailAsync(ProtocolErrorCodes.MessageOutOfOrder, "image_on_v1", token).ConfigureAwait(false);
            return false;
        }

        if (!options.ImageSyncEnabled())
        {
            // Same mid-session gate as the announce path: no image bytes are accepted
            // (or buffered to disk) once the local image_sync setting turned off.
            await FailAsync(ProtocolErrorCodes.UnsupportedMedia, "image_sync_disabled", token).ConfigureAwait(false);
            return false;
        }

        var eventId = Guid.Parse(begin.EventId);
        if (!outstandingFetches.TryGetValue(eventId, out var header)
            || header.ContentHash != begin.ContentHash
            || header.MimeType != begin.MimeType
            || header.EncodedBytes != begin.EncodedBytes)
        {
            await FailAsync(ProtocolErrorCodes.MessageOutOfOrder, "payload_without_fetch", token).ConfigureAwait(false);
            return false;
        }

        if (incomingImages.Count >= MediaLimits.MaxConcurrentDownloads)
        {
            await FailAsync(ProtocolErrorCodes.RateLimited, "too_many_image_downloads", token).ConfigureAwait(false);
            return false;
        }

        if (!await imageDownloadSlots.WaitAsync(0, token).ConfigureAwait(false))
        {
            await FailAsync(ProtocolErrorCodes.RateLimited, "too_many_image_downloads", token).ConfigureAwait(false);
            return false;
        }

        PendingMediaWrite pending;
        try
        {
            pending = store.Media.BeginWrite();
        }
        catch
        {
            imageDownloadSlots.Release();
            await FailAsync(ProtocolErrorCodes.MediaStorageFailed, "temp_open_failed", token).ConfigureAwait(false);
            return false;
        }

        incomingImages[eventId] = new IncomingImageTransfer(
            Guid.Parse(begin.TransferId),
            eventId,
            header,
            pending,
            (int)begin.ChunkCount,
            (int)begin.EncodedBytes,
            begin.ContentHash);
        return true;
    }

    private async Task<bool> HandleClipPayloadChunkAsync(ClipPayloadChunkBody chunk, CancellationToken token)
    {
        var eventId = Guid.Parse(chunk.EventId);
        if (!incomingImages.TryGetValue(eventId, out var transfer)
            || transfer.TransferId != Guid.Parse(chunk.TransferId)
            || transfer.ChunkCount != chunk.ChunkCount)
        {
            await FailAsync(ProtocolErrorCodes.MediaOutOfOrder, "chunk_unbound", token).ConfigureAwait(false);
            return false;
        }

        if (chunk.ChunkIndex != transfer.NextIndex)
        {
            if (chunk.ChunkIndex < transfer.NextIndex)
            {
                return true;
            }

            await FailAsync(ProtocolErrorCodes.MediaOutOfOrder, "chunk_out_of_order", token).ConfigureAwait(false);
            return false;
        }

        if (!ImageChunks.TryDecodeChunk(chunk.Data, (int)chunk.ChunkBytes, out var bytes))
        {
            await FailAsync(ProtocolErrorCodes.MediaDecodeFailed, "chunk_decode", token).ConfigureAwait(false);
            return false;
        }

        try
        {
            MediaBlobStore.Append(transfer.Pending, bytes);
        }
        catch (InvalidDataException)
        {
            await FailAsync(ProtocolErrorCodes.MediaTooLarge, "chunk_overflow", token).ConfigureAwait(false);
            return false;
        }

        transfer.NextIndex++;
        return true;
    }

    private async Task<bool> HandleClipPayloadEndAsync(ClipPayloadEndBody end, CancellationToken token)
    {
        var eventId = Guid.Parse(end.EventId);
        if (!incomingImages.Remove(eventId, out var transfer)
            || transfer.TransferId != Guid.Parse(end.TransferId)
            || transfer.ContentHash != end.ContentHash
            || transfer.NextIndex != transfer.ChunkCount)
        {
            await FailAsync(ProtocolErrorCodes.MediaOutOfOrder, "end_unbound", token).ConfigureAwait(false);
            return false;
        }

        imageDownloadSlots.Release();
        outstandingFetches.Remove(eventId);
        ValidatedImage validated;
        try
        {
            validated = store.Media.Commit(transfer.Pending, transfer.ContentHash, transfer.Header.MimeType);
        }
        catch (InvalidDataException exception)
        {
            var code = exception.Message switch
            {
                "MEDIA_HASH_MISMATCH" => ProtocolErrorCodes.MediaHashMismatch,
                "MEDIA_TOO_LARGE" => ProtocolErrorCodes.MediaTooLarge,
                "UNSUPPORTED_MEDIA" => ProtocolErrorCodes.UnsupportedMedia,
                "MEDIA_DECODE_FAILED" => ProtocolErrorCodes.MediaDecodeFailed,
                _ => ProtocolErrorCodes.MediaStorageFailed
            };
            await FailAsync(code, "image_commit_failed", token).ConfigureAwait(false);
            return false;
        }

        var remoteEvent = new RemoteClipEvent(
            eventId,
            transfer.Header.OriginDeviceId,
            transfer.Header.OriginSeq,
            Content: null,
            validated.ContentHash,
            transfer.Header.SourceApp,
            DateTimeOffset.FromUnixTimeMilliseconds(transfer.Header.CreatedAtMs!.Value),
            transfer.Header.ExpiresAtMs is null
                ? null
                : DateTimeOffset.FromUnixTimeMilliseconds(transfer.Header.ExpiresAtMs.Value),
            Kind: "image",
            MimeType: validated.MimeType,
            EncodedBytes: validated.EncodedBytes,
            PixelWidth: validated.PixelWidth,
            PixelHeight: validated.PixelHeight);
        var stored = await store.StoreRemoteEventAsync(remoteEvent, peerDevice!.DeviceId, token).ConfigureAwait(false);
        if (stored is RemoteStoreResult.IdentityConflict conflict)
        {
            PeerLog.StoreConflict(logger, "image_payload", conflict.Detail);
            await FailAsync(ProtocolErrorCodes.EventConflict, "image_payload_conflict", token).ConfigureAwait(false);
            return false;
        }

        await SendAcksAsync([(transfer.Header.OriginDeviceId, transfer.Header.OriginSeq)], token).ConfigureAwait(false);
        if (stored is RemoteStoreResult.Stored)
        {
            RaiseCommitted(
            [
                new RemoteClipApplied(
                    eventId,
                    transfer.Header.OriginDeviceId,
                    transfer.Header.OriginSeq,
                    string.Empty,
                    remoteEvent.CreatedAt,
                    "image",
                    validated.ContentHash,
                    validated.MimeType)
            ]);
        }

        if (wantBacklogPending)
        {
            await SendWantsAsync(token).ConfigureAwait(false);
        }

        return true;
    }

    private static IEnumerable<IReadOnlyList<ClipPayloadItemDto>> ChunkPayloads(IReadOnlyList<ClipPayloadItemDto> items)
    {
        var batch = new List<ClipPayloadItemDto>();
        long batchBytes = 0;
        foreach (var item in items)
        {
            if (batch.Count > 0
                && (batch.Count >= ProtocolLimits.MaxPayloadClips
                    || batchBytes + item.Utf8Bytes > ProtocolLimits.MaxPayloadBatchContentBytes))
            {
                yield return batch;
                batch = [];
                batchBytes = 0;
            }

            batch.Add(item);
            batchBytes += item.Utf8Bytes;
        }

        if (batch.Count > 0)
        {
            yield return batch;
        }
    }

    private bool TryResolveSecret(PairedDevice device, out byte[] secret)
    {
        secret = [];
        if (string.IsNullOrEmpty(device.PairSecretProtected))
        {
            return false;
        }

        try
        {
            var decoded = secretProtector.Unprotect(Convert.FromBase64String(device.PairSecretProtected));
            if (decoded.Length != PairAuthProof.SecretLength)
            {
                return false;
            }

            secret = decoded;
            return true;
        }
        catch (Exception exception) when (exception is FormatException or CryptographicException)
        {
            return false;
        }
    }

    private async Task FailAsync(string errorCode, string detail, CancellationToken token)
    {
        Complete(new SyncSessionResult(IsAuthenticated, errorCode, detail));
        try
        {
            await SendAsync(ProtocolMessageTypes.Error, new ErrorBody
            {
                Code = errorCode,
                Retryable = false
            }, token).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is WebSocketException or OperationCanceledException or ObjectDisposedException or InvalidOperationException)
        {
            // Best effort: the peer may already be gone.
        }

        await sendLock.WaitAsync(CancellationToken.None).ConfigureAwait(false);
        try
        {
            await transport.CloseAsync(WebSocketCloseStatus.PolicyViolation, errorCode, CancellationToken.None).ConfigureAwait(false);
        }
        finally
        {
            sendLock.Release();
        }
    }

    private void Complete(SyncSessionResult result)
    {
        lock (completionLock)
        {
            completion ??= result;
        }
    }

    private Task SendAsync(string type, object body, CancellationToken token) =>
        SendWithRequestIdAsync(type, Guid.NewGuid(), body, token);

    private async Task SendWithRequestIdAsync(string type, Guid requestId, object body, CancellationToken token)
    {
        var json = ProtocolWriter.Serialize(protocolVersion, type, requestId, body);
        await sendLock.WaitAsync(token).ConfigureAwait(false);
        try
        {
            await transport.SendTextAsync(json, token).ConfigureAwait(false);
        }
        finally
        {
            sendLock.Release();
        }

        PeerLog.MessageSent(logger, type);
    }

    /// <summary>
    /// After our close frame is out, waits briefly for the peer's close so the socket owner
    /// does not dispose into a TCP reset that would eat the final error frame on the wire.
    /// </summary>
    private async Task DrainUntilPeerClosesAsync()
    {
        try
        {
            using var drainCts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            for (var frames = 0; frames < 64; frames++)
            {
                if (await transport.ReceiveAsync(drainCts.Token).ConfigureAwait(false) is TransportFrame.Closed)
                {
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private static async Task AwaitQuietlyAsync(Task? task)
    {
        if (task is null)
        {
            return;
        }

        try
        {
            await task.ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Loop teardown already recorded the failure; nothing else to do here.
        }
    }

    public void Dispose()
    {
        sendLock.Dispose();
        imageDownloadSlots.Dispose();
        foreach (var transfer in incomingImages.Values)
        {
            transfer.Pending.Dispose();
        }

        incomingImages.Clear();
    }

    private enum SessionState
    {
        ExpectHello,
        ExpectChallenge,
        ExpectAuth,
        Ready
    }

    private sealed record OutstandingChallenge(Guid RequestId, byte[] Nonce, long TrustEpoch, DateTimeOffset ExpiresAt);

    private sealed class IncomingImageTransfer(
        Guid transferId,
        Guid eventId,
        ClipHeaderDto header,
        PendingMediaWrite pending,
        int chunkCount,
        int encodedBytes,
        string contentHash)
    {
        public Guid TransferId { get; } = transferId;

        public Guid EventId { get; } = eventId;

        public ClipHeaderDto Header { get; } = header;

        public PendingMediaWrite Pending { get; } = pending;

        public int ChunkCount { get; } = chunkCount;

        public int EncodedBytes { get; } = encodedBytes;

        public string ContentHash { get; } = contentHash;

        public int NextIndex { get; set; }
    }

    private enum ReplayVerdict
    {
        Fresh,
        IdenticalRetry,
        Conflict
    }

    /// <summary>Bounded request-id replay detector per protocol section 2.</summary>
    private sealed class ReplayWindow(int capacity)
    {
        private readonly Dictionary<Guid, string> hashes = [];
        private readonly Queue<Guid> order = [];

        public ReplayVerdict Classify(Guid requestId, string rawFrame)
        {
            var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(rawFrame)));
            if (hashes.TryGetValue(requestId, out var existing))
            {
                return string.Equals(existing, hash, StringComparison.Ordinal)
                    ? ReplayVerdict.IdenticalRetry
                    : ReplayVerdict.Conflict;
            }

            hashes[requestId] = hash;
            order.Enqueue(requestId);
            if (order.Count > capacity)
            {
                hashes.Remove(order.Dequeue());
            }

            return ReplayVerdict.Fresh;
        }
    }
}

/// <summary>Lets the hosting server rate-limit failed authentication attempts across sessions.</summary>
public interface IAuthFailureSink
{
    void RecordAuthFailure(string deviceId);

    bool IsThrottled(string deviceId);
}
