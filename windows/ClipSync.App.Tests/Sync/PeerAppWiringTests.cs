using System.IO;
using System.Security.Cryptography;
using ClipSync.App.Security;
using ClipSync.App.Sync;
using ClipSync.Peer.Security;

namespace ClipSync.App.Tests.Sync;

public sealed class PeerAppWiringTests
{
    [Fact]
    public void DpapiProtectorRoundTripsAndCiphertextDiffers()
    {
        var protector = new DpapiSecretProtector();
        var secret = RandomNumberGenerator.GetBytes(32);

        var protectedBytes = protector.Protect(secret);
        Assert.False(protectedBytes.AsSpan().SequenceEqual(secret));
        Assert.Equal(secret, protector.Unprotect(protectedBytes));
    }

    [Fact]
    public void CertificateProviderKeepsFingerprintStableAcrossReloads()
    {
        var directory = Path.Combine(Path.GetTempPath(), "clipsync-app-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var protector = new DpapiSecretProtector();
            const string deviceId = "11111111-1111-4111-8111-111111111111";

            using var first = PeerCertificateProvider.GetOrCreate(directory, deviceId, protector);
            using var second = PeerCertificateProvider.GetOrCreate(directory, deviceId, protector);
            Assert.Equal(PeerCertificate.Fingerprint(first), PeerCertificate.Fingerprint(second));
            Assert.True(second.HasPrivateKey);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void CorruptCertificateFileTriggersRegeneration()
    {
        var directory = Path.Combine(Path.GetTempPath(), "clipsync-app-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var protector = new DpapiSecretProtector();
            const string deviceId = "11111111-1111-4111-8111-111111111111";

            using var first = PeerCertificateProvider.GetOrCreate(directory, deviceId, protector);
            var path = Directory.GetFiles(directory).Single();
            File.WriteAllBytes(path, [1, 2, 3, 4]);

            using var regenerated = PeerCertificateProvider.GetOrCreate(directory, deviceId, protector);
            Assert.NotEqual(PeerCertificate.Fingerprint(first), PeerCertificate.Fingerprint(regenerated));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void BindAddressResolutionAddsValidExtrasAndSkipsGarbage()
    {
        var addresses = PeerSyncHost.ResolveBindAddresses("100.100.1.7, not-an-ip; 100.100.1.7\n192.0.2.1");

        Assert.Contains(System.Net.IPAddress.Loopback, addresses);
        Assert.Contains(System.Net.IPAddress.Parse("100.100.1.7"), addresses);
        Assert.Contains(System.Net.IPAddress.Parse("192.0.2.1"), addresses);

        // The duplicate extra appears once, and the garbage token is dropped.
        Assert.Single(addresses, address => address.Equals(System.Net.IPAddress.Parse("100.100.1.7")));
    }
}
