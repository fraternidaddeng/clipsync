using System.Security.Cryptography;

namespace ClipSync.Core.Security.Bt1;

/// <summary>Per-direction AES-256-GCM session keys derived for one bt1 connection.</summary>
public sealed record Bt1SessionKeys(ReadOnlyMemory<byte> ClientToListener, ReadOnlyMemory<byte> ListenerToClient);

/// <summary>
/// bt1 session-key derivation from docs/protocol-bt1.md section 4:
/// HKDF-SHA-256(ikm=pair_secret, salt=nonce_c||nonce_l, info="ClipSync/bt1/keys") expanded
/// to 64 bytes; the first half keys client-to-listener, the second half listener-to-client.
/// The shared reference vectors live in protocol/bt1/fixtures/handshake/vectors.json.
/// </summary>
public static class Bt1KeySchedule
{
    public const int KeyLength = 32;

    private static readonly byte[] Info = "ClipSync/bt1/keys"u8.ToArray();

    public static Bt1SessionKeys Derive(
        ReadOnlySpan<byte> pairSecret,
        ReadOnlySpan<byte> nonceClient,
        ReadOnlySpan<byte> nonceListener)
    {
        if (pairSecret.Length != Bt1AuthProof.SecretLength)
        {
            throw new ArgumentException("The pair secret must be exactly 32 bytes.", nameof(pairSecret));
        }

        if (nonceClient.Length != Bt1AuthProof.NonceLength)
        {
            throw new ArgumentException("The client nonce must be exactly 32 bytes.", nameof(nonceClient));
        }

        if (nonceListener.Length != Bt1AuthProof.NonceLength)
        {
            throw new ArgumentException("The listener nonce must be exactly 32 bytes.", nameof(nonceListener));
        }

        Span<byte> salt = stackalloc byte[Bt1AuthProof.NonceLength * 2];
        nonceClient.CopyTo(salt);
        nonceListener.CopyTo(salt[Bt1AuthProof.NonceLength..]);

        var okm = new byte[KeyLength * 2];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, pairSecret, okm, salt, Info);

        return new Bt1SessionKeys(okm.AsMemory(0, KeyLength), okm.AsMemory(KeyLength, KeyLength));
    }
}
