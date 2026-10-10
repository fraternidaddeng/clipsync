using System.Security.Cryptography;

namespace ClipSync.Core.Update;

/// <summary>
/// Release signature check: ECDSA P-256 / SHA-256 over the payload bytes, verified against a
/// public key compiled into the client. The <c>.sig</c> asset carries the DER (RFC 3279)
/// signature, base64-encoded, as produced by <c>scripts/sign-release.py</c> or
/// <c>openssl dgst -sha256 -sign key.pem | base64</c>. The private key never enters the repo.
/// </summary>
public static class UpdateSignature
{
    /// <summary>
    /// Base64 SubjectPublicKeyInfo of the release signing key. Empty until the maintainer
    /// generates a keypair (docs/release-signing.md); while empty, every update is refused
    /// for automatic installation.
    /// </summary>
    public const string EmbeddedPublicKeySpki = "";

    public const string SignatureSuffix = ".sig";

    public static bool IsConfigured(string? publicKeySpki = null) =>
        !string.IsNullOrWhiteSpace(publicKeySpki ?? EmbeddedPublicKeySpki);

    public static ReleaseAsset? FindSignature(GitHubLatestRelease release, ReleaseAsset payload) =>
        release.Assets.FirstOrDefault(asset =>
            string.Equals(asset.Name, payload.Name + SignatureSuffix, StringComparison.OrdinalIgnoreCase));

    /// <summary>True only for a well-formed signature that verifies under the key.</summary>
    public static bool Verify(Stream payload, string signatureText, string? publicKeySpki = null)
    {
        ArgumentNullException.ThrowIfNull(payload);
        var key = publicKeySpki ?? EmbeddedPublicKeySpki;
        if (string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(signatureText))
        {
            return false;
        }

        try
        {
            var signature = Convert.FromBase64String(signatureText.Trim());
            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(key.Trim()), out _);
            if (payload.CanSeek)
            {
                payload.Position = 0;
            }

            return ecdsa.VerifyData(payload, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (Exception exception) when (exception is FormatException or CryptographicException)
        {
            return false;
        }
    }
}

/// <summary>The payload is unsigned, the signing key is not configured, or the signature is bad.</summary>
public sealed class UpdateSignatureException(string message) : InvalidOperationException(message);
