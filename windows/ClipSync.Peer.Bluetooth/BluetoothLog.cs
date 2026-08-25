using Microsoft.Extensions.Logging;

namespace ClipSync.Peer.Bluetooth;

/// <summary>
/// Source-generated log messages for the Bluetooth fallback listener. Messages carry
/// codes, reasons, and device IDs only; clipboard content, nonces, proofs, secrets, and
/// derived keys never reach a template argument (docs/protocol-bt1.md section 6).
/// </summary>
public static partial class BluetoothLog
{
    [LoggerMessage(EventId = 1, Level = LogLevel.Information, Message = "bluetooth listener started")]
    public static partial void ListenerStarted(ILogger logger);

    [LoggerMessage(EventId = 2, Level = LogLevel.Information, Message = "bluetooth listener stopped")]
    public static partial void ListenerStopped(ILogger logger);

    [LoggerMessage(EventId = 3, Level = LogLevel.Warning, Message = "bluetooth listener failed: {ExceptionKind}")]
    public static partial void ListenerFailed(ILogger logger, string exceptionKind);

    [LoggerMessage(EventId = 4, Level = LogLevel.Information, Message = "bt1 handshake refused code={Code} reason={Reason}")]
    public static partial void HandshakeRefused(ILogger logger, string code, string reason);

    [LoggerMessage(EventId = 5, Level = LogLevel.Information, Message = "bt1 session started device={DeviceId}")]
    public static partial void SessionStarted(ILogger logger, string deviceId);

    [LoggerMessage(EventId = 6, Level = LogLevel.Information, Message = "bt1 session ended device={DeviceId} code={Code}")]
    public static partial void SessionEnded(ILogger logger, string deviceId, string code);

    [LoggerMessage(EventId = 7, Level = LogLevel.Warning, Message = "bluetooth accept rate-limited remote connection")]
    public static partial void AcceptRateLimited(ILogger logger);

    [LoggerMessage(EventId = 8, Level = LogLevel.Debug, Message = "bt1 handshake aborted: {ExceptionKind}")]
    public static partial void HandshakeAborted(ILogger logger, string exceptionKind);
}
