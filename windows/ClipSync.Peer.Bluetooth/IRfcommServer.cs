namespace ClipSync.Peer.Bluetooth;

/// <summary>
/// Platform seam for the Windows RFCOMM listener (ADR 0005 phase 2). The WinRT
/// implementation (RfcommServiceProvider + StreamSocketListener) stays behind this
/// interface so the bt1 handshake, the frame pump, and the ISyncTransport adapter can
/// all be unit-tested against in-memory duplex streams with no Bluetooth radio and no
/// WinRT dependency.
/// </summary>
public interface IRfcommServer : IAsyncDisposable
{
    /// <summary>
    /// Publishes the SDP record for <see cref="RfcommContract.ServiceUuid"/> and starts
    /// listening. Fails (rather than silently degrading) when the adapter is absent or
    /// the radio is off; the caller owns retry/backoff via the existing Resilience hooks.
    /// </summary>
    ValueTask StartAsync(CancellationToken cancellationToken);

    /// <summary>
    /// Waits for the next inbound connection. ClipSync accepts a single active Bluetooth
    /// session at a time; the caller must dispose the previous connection first.
    /// </summary>
    ValueTask<IRfcommConnection> AcceptAsync(CancellationToken cancellationToken);

    /// <summary>Withdraws the SDP record and stops accepting. Idempotent.</summary>
    ValueTask StopAsync(CancellationToken cancellationToken);
}

/// <summary>One accepted RFCOMM connection, exposed as a raw duplex byte stream.</summary>
public interface IRfcommConnection : IAsyncDisposable
{
    /// <summary>
    /// The reliable byte stream the bt1 channel runs on. bt1 owns everything above it:
    /// authentication, framing, and encryption per docs/protocol-bt1.md.
    /// </summary>
    Stream Stream { get; }

    /// <summary>
    /// Transport-level remote identifier (Bluetooth address) for logs and diagnostics
    /// only. Never an authentication input: bt1 authenticates with the pair secret.
    /// </summary>
    string RemoteAddress { get; }
}
