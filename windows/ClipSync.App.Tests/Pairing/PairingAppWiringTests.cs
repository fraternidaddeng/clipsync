using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App.Pairing;
using ClipSync.App.Security;
using ClipSync.App.Sync;
using ClipSync.Core.Storage;
using ClipSync.Peer.Pairing;
using ClipSync.Peer.Security;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.Pairing;

public sealed class PairingAppWiringTests
{
    private const string WindowsDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string AndroidDeviceId = "22222222-2222-4222-8222-222222222222";

    [Fact]
    public void QrRendererProducesPngBytes()
    {
        var png = PairingQrRenderer.RenderPng("{\"kind\":\"pairing_qr\"}");

        // PNG magic: 89 50 4E 47 0D 0A 1A 0A.
        Assert.True(png.Length > 8);
        Assert.Equal(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }, png.Take(8).ToArray());
    }

    [Fact]
    public void FingerprintFormatterGroupsHexIntoBlocks()
    {
        var formatted = PairingQrRenderer.FormatFingerprint("aabbccddeeff0011");
        Assert.Equal("aabb ccdd eeff 0011", formatted);
    }

    [Theory]
    [InlineData(1.0, 45, 280, 6)] // 100%: round(280 / 45)
    [InlineData(1.25, 45, 280, 8)] // 125%: round(350 / 45)
    [InlineData(1.5, 45, 280, 9)] // 150%: round(420 / 45)
    [InlineData(2.0, 45, 280, 12)] // 200%: round(560 / 45)
    [InlineData(1.0, 1000, 280, 1)] // never below one whole pixel per module
    public void PixelsPerModuleIsWholePhysicalPixelsNearestTheTargetEdge(
        double pixelsPerDip, int moduleCount, double targetEdgeDips, int expected)
    {
        Assert.Equal(expected, PairingQrRenderer.PixelsPerModule(pixelsPerDip, moduleCount, targetEdgeDips));
    }

    [Fact]
    public void DpiAwareRenderReportsTheExactPngPixelEdge()
    {
        var rendered = PairingQrRenderer.RenderPngForDpi("{\"kind\":\"pairing_qr\"}", pixelsPerDip: 1.5, targetEdgeDips: 280);

        // IHDR width/height are big-endian at offsets 16 and 20 of a PNG stream.
        var width = (rendered.Png[16] << 24) | (rendered.Png[17] << 16) | (rendered.Png[18] << 8) | rendered.Png[19];
        var height = (rendered.Png[20] << 24) | (rendered.Png[21] << 16) | (rendered.Png[22] << 8) | rendered.Png[23];
        Assert.Equal(rendered.PixelEdge, width);
        Assert.Equal(rendered.PixelEdge, height);
    }

    [Fact]
    public async Task HostServesPairConfirmEndToEnd()
    {
        var directory = Path.Combine(Path.GetTempPath(), "clipsync-app-pairing", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var protector = new DpapiSecretProtector();
        var store = new SqliteClipboardEventStore(Path.Combine(directory, "wiring.db"), WindowsDeviceId);
        await store.InitializeAsync();
        using var certificate = PeerCertificate.CreateSelfSigned(WindowsDeviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(30));
        var pairing = new PairingService(
            store,
            protector,
            new AutoApprover(),
            new PairingServiceOptions { LocalDisplayName = "DESKTOP-TEST" });
        await using var host = new PeerSyncHost(store, protector, certificate, pairing);
        try
        {
            await host.StartAsync(extraBindAddresses: null);

            // The QR advertises only phone-reachable hosts, never loopback.
            Assert.DoesNotContain("127.0.0.1", host.ReachableHosts);

            var ticket = pairing.IssueTicket();
            var payload = pairing.BuildQrPayload(ticket, ["192.0.2.10"], host.Port, host.CertificateFingerprint);
            Assert.DoesNotContain("pair_secret", PairingJson.Serialize(payload), StringComparison.Ordinal);

            using var client = CreatePinnedClient(host);
            var requestJson = PairingJson.Serialize(new PairingConfirmRequest
            {
                Kind = PairingDocumentKinds.ConfirmRequest,
                Version = 1,
                Token = ticket.Token,
                DeviceId = AndroidDeviceId,
                DisplayName = "Pixel 8",
                Platform = "android"
            });
            using var response = await client.PostAsync(
                new Uri("/v1/pair/confirm", UriKind.Relative),
                new StringContent(requestJson, Encoding.UTF8, "application/json"));

            Assert.Equal(System.Net.HttpStatusCode.OK, response.StatusCode);
            var confirm = PairingJson.ParseConfirmResponse(await response.Content.ReadAsStringAsync(), out var error);
            Assert.Null(error);
            Assert.NotNull(confirm);
            Assert.Equal(WindowsDeviceId, confirm.DeviceId);

            var device = await store.GetDeviceAsync(AndroidDeviceId);
            Assert.NotNull(device);
            Assert.Equal("Pixel 8", device.DisplayName);
        }
        finally
        {
            await host.DisposeAsync();
            await store.DisposeAsync();
            SqliteConnection.ClearAllPools();
            Directory.Delete(directory, recursive: true);
        }
    }

    private static HttpClient CreatePinnedClient(PeerSyncHost host)
    {
        var handler = new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
                certificate is not null
                && string.Equals(
                    Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant(),
                    host.CertificateFingerprint,
                    StringComparison.Ordinal)
        };
        var client = new HttpClient(handler) { BaseAddress = new Uri($"https://127.0.0.1:{host.Port}") };
        client.DefaultRequestHeaders.Add("X-Protocol-Version", "1");
        return client;
    }

    private sealed class AutoApprover : IPairingApprover
    {
        public Task<bool> ApproveAsync(PairingCandidate candidate, CancellationToken cancellationToken) =>
            Task.FromResult(true);
    }
}
