using System.Net.WebSockets;

namespace ClipSync.Peer.Transport;

/// <summary>One received WebSocket message, already assembled from fragments.</summary>
public abstract record TransportFrame
{
    private TransportFrame()
    {
    }

    public sealed record Text(string Payload) : TransportFrame;

    /// <summary>The peer closed the socket (or the socket died).</summary>
    public sealed record Closed : TransportFrame;

    /// <summary>A text message exceeded the configured limit; the engine must close.</summary>
    public sealed record TooLarge : TransportFrame;

    /// <summary>Protocol v1 forbids binary frames.</summary>
    public sealed record Binary : TransportFrame;
}

/// <summary>
/// Transport abstraction for one sync session so the engine stays independent of
/// server/client WebSocket wiring and tests can run in-memory.
/// </summary>
public interface ISyncTransport : IAsyncDisposable
{
    ValueTask<TransportFrame> ReceiveAsync(CancellationToken cancellationToken);

    ValueTask SendTextAsync(string payload, CancellationToken cancellationToken);

    ValueTask CloseAsync(WebSocketCloseStatus status, string description, CancellationToken cancellationToken);
}
