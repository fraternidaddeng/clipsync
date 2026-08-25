using System.Net.WebSockets;
using System.Text;
using ClipSync.Core.Protocol;

namespace ClipSync.Peer.Transport;

/// <summary>
/// Wraps a connected WebSocket: assembles fragmented text messages up to the protocol
/// frame limit, rejects binary frames, and normalizes close/abort into frames.
/// </summary>
public sealed class WebSocketSyncTransport(
    WebSocket socket,
    int maxTextMessageBytes = ProtocolLimits.MaxWebSocketTextMessageBytes) : ISyncTransport
{
    private readonly byte[] receiveBuffer = new byte[64 * 1024];

    public async ValueTask<TransportFrame> ReceiveAsync(CancellationToken cancellationToken)
    {
        var message = new MemoryStream();
        try
        {
            while (true)
            {
                var result = await socket.ReceiveAsync(receiveBuffer, cancellationToken).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    return new TransportFrame.Closed();
                }

                if (result.MessageType == WebSocketMessageType.Binary)
                {
                    return new TransportFrame.Binary();
                }

                if (message.Length + result.Count > maxTextMessageBytes)
                {
                    return new TransportFrame.TooLarge();
                }

                message.Write(receiveBuffer, 0, result.Count);

                if (result.EndOfMessage)
                {
                    return new TransportFrame.Text(Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length));
                }
            }
        }
        catch (Exception exception) when (exception is WebSocketException or OperationCanceledException or ObjectDisposedException)
        {
            if (exception is OperationCanceledException && cancellationToken.IsCancellationRequested)
            {
                throw;
            }

            return new TransportFrame.Closed();
        }
    }

    public async ValueTask SendTextAsync(string payload, CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(payload);
        await socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, cancellationToken).ConfigureAwait(false);
    }

    public async ValueTask CloseAsync(WebSocketCloseStatus status, string description, CancellationToken cancellationToken)
    {
        try
        {
            if (socket.State == WebSocketState.CloseReceived)
            {
                await socket.CloseAsync(status, description, cancellationToken).ConfigureAwait(false);
            }
            else if (socket.State == WebSocketState.Open)
            {
                // Send the close frame without waiting for the peer's reply; a stuck or
                // misbehaving peer must not be able to pin this session in CloseAsync.
                await socket.CloseOutputAsync(status, description, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception exception) when (exception is WebSocketException or OperationCanceledException or ObjectDisposedException)
        {
            // The peer may already be gone; closing is best-effort.
        }
    }

    public ValueTask DisposeAsync()
    {
        socket.Dispose();
        return ValueTask.CompletedTask;
    }
}
