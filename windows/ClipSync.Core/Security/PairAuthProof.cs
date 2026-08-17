using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace ClipSync.Core.Security;

/// <summary>
/// Pair-secret challenge-response proof from docs/protocol-v1.md section 3.
/// The shared reference vectors live in protocol/v1/fixtures/auth/vectors.json.
/// </summary>
public static class PairAuthProof
{
    public const int SecretLength = 32;
    public const int NonceLength = 32;
    public const int ProofLength = 32;

    private static readonly byte[] Prefix = "ClipSync/v1/auth\n"u8.ToArray();

    public static byte[] Compute(
        ReadOnlySpan<byte> pairSecret,
        Guid challengeRequestId,
        ReadOnlySpan<byte> nonce,
        Guid challengerDeviceId,
        Guid responderDeviceId,
        long trustEpoch)
    {
        if (pairSecret.Length != SecretLength)
        {
            throw new ArgumentException("The pair secret must be exactly 32 bytes.", nameof(pairSecret));
        }

        if (nonce.Length != NonceLength)
        {
            throw new ArgumentException("The challenge nonce must be exactly 32 bytes.", nameof(nonce));
        }

        var requestIdBytes = Encoding.UTF8.GetBytes(challengeRequestId.ToString("D"));
        var message = new byte[Prefix.Length + requestIdBytes.Length + 1 + NonceLength + 16 + 16 + 8];
        var offset = 0;

        Prefix.CopyTo(message, offset);
        offset += Prefix.Length;
        requestIdBytes.CopyTo(message, offset);
        offset += requestIdBytes.Length;
        message[offset] = 0x00;
        offset += 1;
        nonce.CopyTo(message.AsSpan(offset));
        offset += NonceLength;
        WriteUuidBigEndian(challengerDeviceId, message.AsSpan(offset, 16));
        offset += 16;
        WriteUuidBigEndian(responderDeviceId, message.AsSpan(offset, 16));
        offset += 16;
        BinaryPrimitives.WriteInt64BigEndian(message.AsSpan(offset, 8), trustEpoch);

        return HMACSHA256.HashData(pairSecret, message);
    }

    public static bool Verify(
        ReadOnlySpan<byte> pairSecret,
        Guid challengeRequestId,
        ReadOnlySpan<byte> nonce,
        Guid challengerDeviceId,
        Guid responderDeviceId,
        long trustEpoch,
        ReadOnlySpan<byte> proof)
    {
        if (proof.Length != ProofLength)
        {
            return false;
        }

        var expected = Compute(pairSecret, challengeRequestId, nonce, challengerDeviceId, responderDeviceId, trustEpoch);
        return CryptographicOperations.FixedTimeEquals(expected, proof);
    }

    /// <summary>RFC 4122 big-endian byte order, matching UUID_BYTES in the protocol document.</summary>
    private static void WriteUuidBigEndian(Guid value, Span<byte> destination)
    {
        var hex = value.ToString("N");
        for (var index = 0; index < 16; index++)
        {
            destination[index] = byte.Parse(hex.AsSpan(index * 2, 2), System.Globalization.NumberStyles.HexNumber, System.Globalization.CultureInfo.InvariantCulture);
        }
    }
}
