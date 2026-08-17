using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace ClipSync.Peer.Security;

/// <summary>
/// Self-signed TLS identity for the peer endpoint. Trust comes exclusively from the
/// SHA-256 fingerprint pinned during pairing, never from a chain or hostname.
/// </summary>
public static class PeerCertificate
{
    /// <summary>Creates a new self-signed ECDSA P-256 certificate for this device.</summary>
    public static X509Certificate2 CreateSelfSigned(string deviceId, DateTimeOffset now, TimeSpan lifetime)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);

        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(
            $"CN=ClipSync {deviceId}",
            key,
            HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyAgreement,
            critical: true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid("1.3.6.1.5.5.7.3.1")], // serverAuth
            critical: false));

        using var certificate = request.CreateSelfSigned(now.AddDays(-1), now.Add(lifetime));
        // Schannel requires the key in a persistable container; re-import via PFX bytes.
        var pfx = certificate.Export(X509ContentType.Pfx);
        return new X509Certificate2(pfx, (string?)null, X509KeyStorageFlags.Exportable);
    }

    /// <summary>Lowercase hex SHA-256 over the DER-encoded certificate, the pin format everywhere in v1.</summary>
    public static string Fingerprint(X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        return Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant();
    }
}
