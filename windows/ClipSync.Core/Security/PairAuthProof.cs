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

    private static readonly byte[] PrefixV1 = "ClipSync/v1/auth\n"u8.ToArray();
    private static readonly byte[] PrefixV2 = "ClipSync/v2/auth\n"u8.ToArray();

    public static byte[] Compute(
        ReadOnlySpan<byte> pairSecret,
        Guid challengeRequestId,
        ReadOnlySpan<byte> nonce,
        Guid challengerDeviceId,
        Guid responderDeviceId,
        long trustEpoch) =>
        Compute(pairSecret, challengeRequestId, nonce, challengerDeviceId, responderDeviceId, trustEpoch, protocolVersion: 1);

    public static byte[] Compute(
        ReadOnlySpan<byte> pairSecret,
        Guid challengeRequestId,
        ReadOnlySpan<byte> nonce,
        Guid challengerDeviceId,
        Guid responderDeviceId,
        long trustEpoch,
        int protocolVersion)
    {
        if (pairSecret.Length != SecretLength)
        {
            throw new ArgumentException("The pair secret must be exactly 32 bytes.", nameof(pairSecret));
        }

        if (nonce.Length != NonceLength)
        {
            throw new ArgumentException("The challenge nonce must be exactly 32 bytes.", nameof(nonce));
        }

        if (protocolVersion is not (1 or 2))
        {
            throw new ArgumentOutOfRangeException(nameof(protocolVersion), "Protocol version must be 1 or 2.");
        }

        var prefix = protocolVersion == 2 ? PrefixV2 : PrefixV1;
        var requestIdBytes = Encoding.UTF8.GetBytes(challengeRequestId.ToString("D"));
        var extra = protocolVersion == 2 ? 1 + 8 : 0;
        var message = new byte[prefix.Length + requestIdBytes.Length + 1 + NonceLength + 16 + 16 + 8 + extra];
        var offset = 0;

        prefix.CopyTo(message, offset);
        offset += prefix.Length;
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
        offset += 8;
        if (protocolVersion == 2)
        {
            message[offset] = 0x00;
            offset += 1;
            BinaryPrimitives.WriteInt64BigEndian(message.AsSpan(offset, 8), protocolVersion);
        }

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

        return Verify(pairSecret, challengeRequestId, nonce, challengerDeviceId, responderDeviceId, trustEpoch, proof, protocolVersion: 1);
    }

    public static bool Verify(
        ReadOnlySpan<byte> pairSecret,
        Guid challengeRequestId,
        ReadOnlySpan<byte> nonce,
        Guid challengerDeviceId,
        Guid responderDeviceId,
        long trustEpoch,
        ReadOnlySpan<byte> proof,
        int protocolVersion)
    {
        if (proof.Length != ProofLength)
        {
            return false;
        }

        var expected = Compute(
            pairSecret,
            challengeRequestId,
            nonce,
            challengerDeviceId,
            responderDeviceId,
            trustEpoch,
            protocolVersion);
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
