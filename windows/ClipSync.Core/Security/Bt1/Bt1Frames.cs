using System.Buffers.Binary;

namespace ClipSync.Core.Security.Bt1;

/// <summary>
/// bt1 frame-layer constants and length-prefix rules from docs/protocol-bt1.md
/// sections 2 and 5. Every byte on the stream belongs to a frame:
/// UINT32_BE(payload_length) || payload.
/// </summary>
public static class Bt1Frames
{
    public const int LengthPrefixLength = 4;
    public const int NonceLength = 12;
    public const int TagLength = 16;

    /// <summary>7 MiB, matching the protocol v1 WebSocket text-message limit.</summary>
    public const int MaxPlaintextLength = 7 * 1024 * 1024;

    /// <summary>Zero-length plaintext is invalid, so the smallest payload is 1 + tag.</summary>
    public const int MinEncryptedPayloadLength = 1 + TagLength;
    public const int MaxEncryptedPayloadLength = MaxPlaintextLength + TagLength;

    /// <summary>Plaintext handshake JSON payloads are 2..4096 bytes.</summary>
    public const int MinHandshakePayloadLength = 2;
    public const int MaxHandshakePayloadLength = 4096;

    /// <summary>Reads the declared payload length from a 4-byte big-endian prefix.</summary>
    public static long ReadDeclaredPayloadLength(ReadOnlySpan<byte> lengthPrefix)
    {
        if (lengthPrefix.Length < LengthPrefixLength)
        {
            throw new ArgumentException("The length prefix must be 4 bytes.", nameof(lengthPrefix));
        }

        return BinaryPrimitives.ReadUInt32BigEndian(lengthPrefix);
    }

    public static bool IsAcceptableHandshakePayloadLength(long declaredLength) =>
        declaredLength is >= MinHandshakePayloadLength and <= MaxHandshakePayloadLength;

    public static bool IsAcceptableEncryptedPayloadLength(long declaredLength) =>
        declaredLength is >= MinEncryptedPayloadLength and <= MaxEncryptedPayloadLength;
}
