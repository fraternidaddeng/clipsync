using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Update;

namespace ClipSync.Tests.Update;

public sealed class UpdateSignatureTests
{
    private static (string Spki, Func<byte[], string> Sign) NewKey()
    {
        var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        return (Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()),
            data => Convert.ToBase64String(key.SignData(data, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence)));
    }

    [Fact]
    public void AcceptsAValidSignatureAndRejectsTampering()
    {
        var (spki, sign) = NewKey();
        var payload = Encoding.UTF8.GetBytes("ClipSync-windows-x64.zip bytes");
        var signature = sign(payload);
        using var good = new MemoryStream(payload);
        Assert.True(UpdateSignature.Verify(good, signature, spki));

        var tampered = (byte[])payload.Clone();
        tampered[0] ^= 1;
        using var bad = new MemoryStream(tampered);
        Assert.False(UpdateSignature.Verify(bad, signature, spki));
    }

    [Fact]
    public void RejectsASignatureFromAnotherKey()
    {
        var (spki, _) = NewKey();
        var (_, otherSign) = NewKey();
        var payload = new byte[] { 1, 2, 3 };
        using var stream = new MemoryStream(payload);
        Assert.False(UpdateSignature.Verify(stream, otherSign(payload), spki));
    }

    [Theory]
    [InlineData("")]
    [InlineData("not base64 !!")]
    [InlineData("AAAA")]
    public void MalformedOrMissingSignatureIsRejected(string signature)
    {
        var (spki, _) = NewKey();
        using var stream = new MemoryStream([1, 2, 3]);
        Assert.False(UpdateSignature.Verify(stream, signature, spki));
    }

    [Fact]
    public void WithoutAConfiguredKeyNothingVerifies()
    {
        var (_, sign) = NewKey();
        var payload = new byte[] { 1, 2, 3 };
        using var stream = new MemoryStream(payload);
        Assert.False(UpdateSignature.Verify(stream, sign(payload), publicKeySpki: ""));
        Assert.False(UpdateSignature.IsConfigured(""));
    }

    [Fact]
    public void FindsTheSigAssetNextToThePayload()
    {
        var payload = new ReleaseAsset("ClipSync-windows-x64.zip", "https://github.com/a/b/c.zip", 1, null);
        var sig = new ReleaseAsset("ClipSync-windows-x64.zip.sig", "https://github.com/a/b/c.zip.sig", 1, null);
        var release = new GitHubLatestRelease("v1.0.0", "https://github.com/x", [payload, sig]);
        Assert.Same(sig, UpdateSignature.FindSignature(release, payload));
    }
}
