using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ClipSync.Core.Security;
using ClipSync.Peer.Security;

namespace ClipSync.App.Security;

/// <summary>
/// Loads the device TLS certificate from disk or creates it once. The PFX bytes are DPAPI
/// protected; the fingerprint must stay stable across restarts because paired devices pin it.
/// </summary>
public static class PeerCertificateProvider
{
    private const string FileName = "peer-certificate.bin";

    public static X509Certificate2 GetOrCreate(string dataDirectory, string deviceId, ISecretProtector protector) =>
        GetOrCreate(dataDirectory, deviceId, protector, out _);

    /// <param name="replacedExisting">
    /// True when a certificate file existed but could not be used (DPAPI key change, corruption,
    /// near expiry) and a new identity was minted: every paired phone will now see a pin
    /// mismatch, so the caller must tell the user to re-pair. The old file is kept as
    /// <c>peer-certificate.bin.bak-*</c> instead of being overwritten.
    /// </param>
    public static X509Certificate2 GetOrCreate(
        string dataDirectory,
        string deviceId,
        ISecretProtector protector,
        out bool replacedExisting)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(dataDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        ArgumentNullException.ThrowIfNull(protector);

        var path = Path.Combine(dataDirectory, FileName);
        var loaded = TryLoad(path, protector);
        if (loaded is not null)
        {
            replacedExisting = false;
            return loaded;
        }

        replacedExisting = File.Exists(path);
        if (replacedExisting)
        {
            File.Move(path, path + ".bak-" + DateTime.UtcNow.ToString("yyyyMMddHHmmss", System.Globalization.CultureInfo.InvariantCulture), overwrite: true);
        }

        var certificate = PeerCertificate.CreateSelfSigned(deviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(3650));
        var pfx = certificate.Export(X509ContentType.Pfx);
        File.WriteAllBytes(path, protector.Protect(pfx));
        CryptographicOperations.ZeroMemory(pfx);
        return certificate;
    }

    private static X509Certificate2? TryLoad(string path, ISecretProtector protector)
    {
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            var pfx = protector.Unprotect(File.ReadAllBytes(path));
            var certificate = new X509Certificate2(pfx, (string?)null, X509KeyStorageFlags.Exportable);
            CryptographicOperations.ZeroMemory(pfx);

            // Regenerate when close to expiry; re-pairing then refreshes the pinned fingerprint.
            if (certificate.NotAfter <= DateTime.UtcNow.AddDays(30))
            {
                certificate.Dispose();
                return null;
            }

            return certificate;
        }
        catch (Exception exception) when (exception is CryptographicException or IOException)
        {
            return null;
        }
    }
}
