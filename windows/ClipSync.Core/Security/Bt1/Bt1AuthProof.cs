using System.Buffers.Binary;
using System.Security.Cryptography;

namespace ClipSync.Core.Security.Bt1;

/// <summary>Sender role in the bt1 handshake: the side that dialed the stream is the client.</summary>
public enum Bt1Role
{
    Client,
    Listener
}

/// <summary>
/// bt1 mutual-authentication proof from docs/protocol-bt1.md section 3. The shared
/// reference vectors live in protocol/bt1/fixtures/handshake/vectors.json. The
/// "ClipSync/bt1/auth\n" domain prefix keeps bt1 proofs and v1/v2 challenge proofs
/// mutually non-replayable; the role byte string keeps the two directions distinct.
/// </summary>
public static class Bt1AuthProof
{
    public const int SecretLength = 32;
    public const int NonceLength = 32;
    public const int ProofLength = 32;

    private static readonly byte[] Prefix = "ClipSync/bt1/auth\n"u8.ToArray();
    private static readonly byte[] ClientRole = "client"u8.ToArray();
    private static readonly byte[] ListenerRole = "listener"u8.ToArray();

    public static byte[] Compute(
        ReadOnlySpan<byte> pairSecret,
        Bt1Role role,
        ReadOnlySpan<byte> nonceClient,
        ReadOnlySpan<byte> nonceListener,
        Guid clientDeviceId,
        Guid listenerDeviceId,
        long trustEpoch)
    {
        if (pairSecret.Length != SecretLength)
        {
            throw new ArgumentException("The pair secret must be exactly 32 bytes.", nameof(pairSecret));
        }

        if (nonceClient.Length != NonceLength)
        {
            throw new ArgumentException("The client nonce must be exactly 32 bytes.", nameof(nonceClient));
        }

        if (nonceListener.Length != NonceLength)
        {
            throw new ArgumentException("The listener nonce must be exactly 32 bytes.", nameof(nonceListener));
        }

        var roleBytes = role == Bt1Role.Client ? ClientRole : ListenerRole;
        var message = new byte[Prefix.Length + roleBytes.Length + 1 + NonceLength + NonceLength + 16 + 16 + 8];
        var offset = 0;

        Prefix.CopyTo(message, offset);
        offset += Prefix.Length;
        roleBytes.CopyTo(message, offset);
        offset += roleBytes.Length;
        message[offset] = 0x00;
        offset += 1;
        nonceClient.CopyTo(message.AsSpan(offset));
        offset += NonceLength;
        nonceListener.CopyTo(message.AsSpan(offset));
        offset += NonceLength;
        WriteUuidBigEndian(clientDeviceId, message.AsSpan(offset, 16));
        offset += 16;
        WriteUuidBigEndian(listenerDeviceId, message.AsSpan(offset, 16));
        offset += 16;
        BinaryPrimitives.WriteInt64BigEndian(message.AsSpan(offset, 8), trustEpoch);

        return HMACSHA256.HashData(pairSecret, message);
    }

    public static bool Verify(
        ReadOnlySpan<byte> pairSecret,
        Bt1Role role,
        ReadOnlySpan<byte> nonceClient,
        ReadOnlySpan<byte> nonceListener,
        Guid clientDeviceId,
        Guid listenerDeviceId,
        long trustEpoch,
        ReadOnlySpan<byte> proof)
    {
        if (proof.Length != ProofLength)
        {
            return false;
        }

        var expected = Compute(pairSecret, role, nonceClient, nonceListener, clientDeviceId, listenerDeviceId, trustEpoch);
        return CryptographicOperations.FixedTimeEquals(expected, proof);
    }

    /// <summary>RFC 4122 big-endian byte order, matching UUID_BYTES in the protocol documents.</summary>
    private static void WriteUuidBigEndian(Guid value, Span<byte> destination)
    {
        if (!value.TryWriteBytes(destination, bigEndian: true, out _))
        {
            throw new ArgumentException("UUID destination must be 16 bytes.", nameof(destination));
        }
    }
}
