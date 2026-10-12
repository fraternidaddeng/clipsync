using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App.Update;
using ClipSync.Core.Update;

namespace ClipSync.App.Tests.Update;

public sealed class WindowsAppUpdaterTests
{
    [Fact]
    public async Task SignedZipWithMatchingHashPreparesAnApplyScript()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var payload = MakeZip();
        var signature = Convert.ToBase64String(key.SignData(payload, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
        var handler = new AssetHandler(payload, signature);
        using var client = new GitHubReleaseClient(handler: handler);
        using var updater = new WindowsAppUpdater("0.1.0", client, Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()));

        var script = await updater.PrepareApplyAsync(Check(payload));
        var staging = script[..^"-apply.cmd".Length];
        try
        {
            Assert.True(File.Exists(script));
            Assert.Equal("signed-test-payload", File.ReadAllText(Path.Combine(staging, "extracted", "ClipSync", PortableUpdateApplier.WindowsExeName)));
            Assert.Contains(AssetHandler.SignatureUrl, handler.Requests);
            Assert.DoesNotContain(handler.Requests, url => url.Contains("ghproxy", StringComparison.Ordinal));
        }
        finally
        {
            var expectedRoot = Path.Combine(Path.GetTempPath(), "clipsync-update") + Path.DirectorySeparatorChar;
            Assert.StartsWith(expectedRoot, Path.GetFullPath(staging), StringComparison.OrdinalIgnoreCase);
            Directory.Delete(staging, recursive: true);
            File.Delete(script);
        }
    }

    [Fact]
    public async Task CorrectSha256CannotBypassASignatureFromAnotherKey()
    {
        using var trusted = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var untrusted = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var payload = MakeZip();
        var signature = Convert.ToBase64String(untrusted.SignData(payload, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
        using var client = new GitHubReleaseClient(handler: new AssetHandler(payload, signature));
        using var updater = new WindowsAppUpdater("0.1.0", client, Convert.ToBase64String(trusted.ExportSubjectPublicKeyInfo()));

        await Assert.ThrowsAsync<UpdateSignatureException>(() => updater.PrepareApplyAsync(Check(payload)));
    }

    [Fact]
    public async Task AnUnconfiguredKeyFailsBeforeAnyNetworkDownload()
    {
        var payload = MakeZip();
        var handler = new AssetHandler(payload, "unused");
        using var client = new GitHubReleaseClient(handler: handler);
        using var updater = new WindowsAppUpdater("0.1.0", client, publicKeySpki: "");

        await Assert.ThrowsAsync<UpdateSignatureException>(() => updater.PrepareApplyAsync(Check(payload)));
        Assert.Empty(handler.Requests);
    }

    private static byte[] MakeZip()
    {
        using var stream = new MemoryStream();
        using (var zip = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: true))
        {
            var entry = zip.CreateEntry("ClipSync/" + PortableUpdateApplier.WindowsExeName);
            using var writer = new StreamWriter(entry.Open(), Encoding.UTF8);
            writer.Write("signed-test-payload");
        }
        return stream.ToArray();
    }

    private static UpdateCheckResult Check(byte[] payload)
    {
        var zip = new ReleaseAsset("ClipSync-windows-x64.zip", AssetHandler.PayloadUrl, payload.Length,
            Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant());
        var signature = new ReleaseAsset(zip.Name + ".sig", AssetHandler.SignatureUrl, 96, null);
        return UpdateCheckResult.From("0.1.0", new GitHubLatestRelease("v0.2.0", "https://github.com/fraternidaddeng/clipsync/releases/tag/v0.2.0", [zip, signature]), UpdatePlatform.Windows);
    }

    private sealed class AssetHandler(byte[] payload, string signature) : HttpMessageHandler
    {
        public const string PayloadUrl = "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-windows-x64.zip";
        public const string SignatureUrl = PayloadUrl + ".sig";
        public List<string> Requests { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!.AbsoluteUri;
            Requests.Add(url);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = url == SignatureUrl ? new StringContent(signature) : new ByteArrayContent(payload)
            });
        }
    }
}
