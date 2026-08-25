using System.Buffers.Binary;
using System.Text;
using ClipSync.Core.Security.Bt1;

namespace ClipSync.Peer.Bluetooth;

/// <summary>
/// A bt1 frame-layer violation detected while reading from the stream, carrying the stable
/// bt1 error code the listener may answer with (handshake phase only). Post-handshake the
/// caller closes the stream without a wire message (docs/protocol-bt1.md section 6).
/// </summary>
public sealed class Bt1FramingException(string errorCode, string reason) : IOException(reason)
{
    public string ErrorCode { get; } = errorCode;
}

/// <summary>
/// Async frame I/O for a bt1 stream (docs/protocol-bt1.md section 2): every byte belongs to
/// UINT32_BE(payload_length) || payload. Works on any duplex <see cref="Stream"/>, so unit
/// tests drive it over in-memory pipes and the WinRT RFCOMM connection supplies socket
/// streams. Declared-length caps are enforced before any payload allocation.
/// </summary>
public static class Bt1StreamFrames
{
    /// <summary>Writes one plaintext handshake frame around the UTF-8 encoding of <paramref name="payloadText"/>.</summary>
    public static async ValueTask WriteHandshakeFrameAsync(Stream stream, string payloadText, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(payloadText);
        var payload = Encoding.UTF8.GetBytes(payloadText);
        if (!Bt1Frames.IsAcceptableHandshakePayloadLength(payload.Length))
        {
            throw new ArgumentException("Handshake payloads must be 2 to 4096 bytes.", nameof(payloadText));
        }

        var frame = new byte[Bt1Frames.LengthPrefixLength + payload.Length];
        BinaryPrimitives.WriteUInt32BigEndian(frame, (uint)payload.Length);
        payload.CopyTo(frame.AsSpan(Bt1Frames.LengthPrefixLength));
        await stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Writes one already-encrypted complete frame (length prefix included).</summary>
    public static async ValueTask WriteFrameAsync(Stream stream, byte[] frame, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(frame);
        await stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Reads one plaintext handshake payload. Returns null when the peer closed cleanly before
    /// the first prefix byte; throws <see cref="Bt1FramingException"/> with BT1_FRAME_TOO_LARGE
    /// or BT1_SCHEMA_VIOLATION on a declared length outside 2..4096, and <see cref="IOException"/>
    /// on a truncated frame.
    /// </summary>
    public static async ValueTask<string?> ReadHandshakePayloadAsync(Stream stream, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(stream);
        var declared = await ReadDeclaredLengthAsync(stream, cancellationToken).ConfigureAwait(false);
        if (declared is null)
        {
            return null;
        }

        if (declared > Bt1Frames.MaxHandshakePayloadLength)
        {
            throw new Bt1FramingException(Bt1ErrorCodes.FrameTooLarge, "handshake frame length exceeds 4096 bytes");
        }

        if (!Bt1Frames.IsAcceptableHandshakePayloadLength(declared.Value))
        {
            throw new Bt1FramingException(Bt1ErrorCodes.SchemaViolation, "handshake frame length is invalid");
        }

        var payload = await ReadExactAsync(stream, (int)declared.Value, cancellationToken).ConfigureAwait(false);
        return Encoding.UTF8.GetString(payload);
    }

    /// <summary>
    /// Reads one encrypted frame payload (the bytes after the length prefix). Returns null on a
    /// clean close at a frame boundary; throws <see cref="Bt1FramingException"/> on a declared
    /// length outside the post-handshake window (the connection must close without attempting
    /// decryption) and <see cref="IOException"/> on a truncated frame.
    /// </summary>
    public static async ValueTask<byte[]?> ReadEncryptedPayloadAsync(Stream stream, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(stream);
        var declared = await ReadDeclaredLengthAsync(stream, cancellationToken).ConfigureAwait(false);
        if (declared is null)
        {
            return null;
        }

        if (!Bt1Frames.IsAcceptableEncryptedPayloadLength(declared.Value))
        {
            throw new Bt1FramingException(Bt1ErrorCodes.FrameTooLarge, "encrypted frame length is outside the accepted window");
        }

        return await ReadExactAsync(stream, (int)declared.Value, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Null on EOF before the first prefix byte; throws when the prefix itself is cut off.</summary>
    private static async ValueTask<long?> ReadDeclaredLengthAsync(Stream stream, CancellationToken cancellationToken)
    {
        var prefix = new byte[Bt1Frames.LengthPrefixLength];
        var offset = 0;
        while (offset < prefix.Length)
        {
            var read = await stream.ReadAsync(prefix.AsMemory(offset), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                if (offset == 0)
                {
                    return null;
                }

                throw new EndOfStreamException("bt1 stream ended inside a frame length prefix");
            }

            offset += read;
        }

        return Bt1Frames.ReadDeclaredPayloadLength(prefix);
    }

    private static async ValueTask<byte[]> ReadExactAsync(Stream stream, int length, CancellationToken cancellationToken)
    {
        var payload = new byte[length];
        var offset = 0;
        while (offset < length)
        {
            var read = await stream.ReadAsync(payload.AsMemory(offset), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                throw new EndOfStreamException("bt1 stream ended inside a frame payload");
            }

            offset += read;
        }

        return payload;
    }
}
