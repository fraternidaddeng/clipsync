using System.Net.WebSockets;
using System.Text;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Transport;

namespace ClipSync.Peer.Bluetooth;

/// <summary>
/// <see cref="ISyncTransport"/> over an established bt1 channel, so the unchanged
/// <see cref="ClipSync.Peer.Sessions.SyncSessionEngine"/> runs on Bluetooth exactly as it
/// does on a WebSocket. Each v1 text message is exactly one encrypted bt1 frame. bt1 has no
/// wire-level close: a graceful close simply closes the stream and the peer sees
/// <see cref="TransportFrame.Closed"/>. Any frame violation (bad declared length, failed
/// tag) is fatal per docs/protocol-bt1.md section 5 — the link dies and the accept loop
/// owns recovery, mirroring the IP path.
/// </summary>
public sealed class Bt1SyncTransport : ISyncTransport
{
    private readonly Stream stream;
    private readonly Bt1FrameEncryptor send;
    private readonly Bt1FrameDecryptor receive;
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private volatile bool disposed;

    public Bt1SyncTransport(Stream stream, Bt1FrameEncryptor send, Bt1FrameDecryptor receive)
    {
        this.stream = stream ?? throw new ArgumentNullException(nameof(stream));
        this.send = send ?? throw new ArgumentNullException(nameof(send));
        this.receive = receive ?? throw new ArgumentNullException(nameof(receive));
    }

    public async ValueTask<TransportFrame> ReceiveAsync(CancellationToken cancellationToken)
    {
        byte[]? payload;
        try
        {
            payload = await Bt1StreamFrames.ReadEncryptedPayloadAsync(stream, cancellationToken).ConfigureAwait(false);
        }
        catch (IOException)
        {
            return new TransportFrame.Closed();
        }
        catch (ObjectDisposedException)
        {
            return new TransportFrame.Closed();
        }

        if (payload is null)
        {
            return new TransportFrame.Closed();
        }

        // A failed tag means loss, reorder, tampering, or a peer bug: the channel state is
        // unrecoverable, so the link reads as closed and the reconnect logic owns recovery.
        if (!receive.TryDecryptPayload(payload, out var plaintext))
        {
            CloseStreamQuietly();
            return new TransportFrame.Closed();
        }

        return new TransportFrame.Text(Encoding.UTF8.GetString(plaintext));
    }

    public async ValueTask SendTextAsync(string payload, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(payload);
        if (disposed)
        {
            throw new IOException("The bt1 link is closed.");
        }

        byte[] frame;
        try
        {
            frame = send.EncryptFrame(Encoding.UTF8.GetBytes(payload));
        }
        catch (ArgumentException failure)
        {
            throw new IOException("The bt1 frame plaintext is outside the accepted window.", failure);
        }
        catch (InvalidOperationException failure)
        {
            throw new IOException("The bt1 send counter is exhausted.", failure);
        }

        await sendLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await Bt1StreamFrames.WriteFrameAsync(stream, frame, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            sendLock.Release();
        }
    }

    public ValueTask CloseAsync(WebSocketCloseStatus status, string description, CancellationToken cancellationToken)
    {
        // bt1 defines no post-handshake close message; closing the stream is the close.
        CloseStreamQuietly();
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        if (!disposed)
        {
            disposed = true;
            CloseStreamQuietly();
            send.Dispose();
            receive.Dispose();
            sendLock.Dispose();
        }

        return ValueTask.CompletedTask;
    }

    private void CloseStreamQuietly()
    {
        try
        {
            stream.Dispose();
        }
        catch (IOException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }
}
