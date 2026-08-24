using Microsoft.Extensions.Logging;

namespace ClipSync.Peer.Diagnostics;

/// <summary>
/// Source-generated log messages for the peer endpoint. Messages carry codes, types,
/// counts, and device IDs only; clipboard content, nonces, proofs, and secrets never
/// reach a template argument.
/// </summary>
public static partial class PeerLog
{
    [LoggerMessage(EventId = 1, Level = LogLevel.Information,
        Message = "session ended role={Role} peer={Peer} authenticated={Authenticated} code={Code} detail={Detail}")]
    public static partial void SessionEnded(ILogger logger, string role, string peer, bool authenticated, string code, string detail);

    [LoggerMessage(EventId = 2, Level = LogLevel.Warning, Message = "rejected frame code={Code} reason={Reason}")]
    public static partial void FrameRejected(ILogger logger, string code, string reason);

    [LoggerMessage(EventId = 3, Level = LogLevel.Debug, Message = "received {Type}")]
    public static partial void MessageReceived(ILogger logger, string type);

    [LoggerMessage(EventId = 4, Level = LogLevel.Debug, Message = "sent {Type}")]
    public static partial void MessageSent(ILogger logger, string type);

    [LoggerMessage(EventId = 5, Level = LogLevel.Warning,
        Message = "peer error code={Code} retryable={Retryable} failedType={FailedType}")]
    public static partial void PeerError(ILogger logger, string code, bool retryable, string failedType);

    [LoggerMessage(EventId = 6, Level = LogLevel.Information, Message = "session ready role={Role} peer={Peer}")]
    public static partial void SessionReady(ILogger logger, string role, string peer);

    [LoggerMessage(EventId = 7, Level = LogLevel.Debug, Message = "skipping announce for untrusted origin {Origin}")]
    public static partial void UntrustedOriginSkipped(ILogger logger, string origin);

    [LoggerMessage(EventId = 8, Level = LogLevel.Warning, Message = "store conflict at {Stage}: {Detail}")]
    public static partial void StoreConflict(ILogger logger, string stage, string detail);

    [LoggerMessage(EventId = 9, Level = LogLevel.Warning, Message = "clip_fetch asked for {Missing} unknown event ids")]
    public static partial void FetchMissingIds(ILogger logger, int missing);

    [LoggerMessage(EventId = 10, Level = LogLevel.Warning, Message = "{Loop} loop stopped: {ExceptionKind}")]
    public static partial void BackgroundLoopStopped(ILogger logger, string loop, string exceptionKind);

    [LoggerMessage(EventId = 11, Level = LogLevel.Information, Message = "peer server listening port={Port} addresses={AddressCount}")]
    public static partial void ServerListening(ILogger logger, int port, int addressCount);

    [LoggerMessage(EventId = 12, Level = LogLevel.Warning, Message = "rejecting connection: session limit {Limit} reached")]
    public static partial void SessionLimitReached(ILogger logger, int limit);

    [LoggerMessage(EventId = 13, Level = LogLevel.Information, Message = "pairing ticket issued expiresAtMs={ExpiresAtMs}")]
    public static partial void PairingTicketIssued(ILogger logger, long expiresAtMs);

    [LoggerMessage(EventId = 14, Level = LogLevel.Warning, Message = "pairing confirm failed code={Code}")]
    public static partial void PairingConfirmFailed(ILogger logger, string code);

    [LoggerMessage(EventId = 15, Level = LogLevel.Information,
        Message = "pairing confirmed device={DeviceId} repair={IsRepair} trustEpoch={TrustEpoch}")]
    public static partial void PairingConfirmed(ILogger logger, string deviceId, bool isRepair, long trustEpoch);

    [LoggerMessage(EventId = 16, Level = LogLevel.Warning,
        Message = "auth rate limit engaged for device={DeviceId}")]
    public static partial void AuthRateLimited(ILogger logger, string deviceId);

    [LoggerMessage(EventId = 17, Level = LogLevel.Warning, Message = "rejecting connection: rate limited kind={Kind}")]
    public static partial void ConnectionRateLimited(ILogger logger, string kind);

    [LoggerMessage(EventId = 18, Level = LogLevel.Information,
        Message = "refusing /v2 sync upgrade: image sync is disabled locally")]
    public static partial void V2RefusedImageSyncDisabled(ILogger logger);
}
