using System.Net;
using System.Text;
using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Peer.Pairing;
using ClipSync.Tests.Peer;

namespace ClipSync.Tests.Pairing;

public sealed class PairingHttpTests
{
    [Fact]
    public async Task FullPairingFlowEndsInAWorkingSyncSession()
    {
        // Nothing is paired up front; the confirm endpoint must establish everything.
        await using var pair = await PeerPair.CreateAsync(
            pairAndroidSide: false,
            pairWindowsSide: false,
            pairingApprover: new DelegateApprover((_, _) => Task.FromResult(true)));
        var ticket = pair.Pairing!.IssueTicket();

        // The QR payload round-trips through the frozen contract, like the Android scanner will.
        var qrJson = PairingJson.Serialize(pair.Pairing.BuildQrPayload(
            ticket,
            ["127.0.0.1"],
            pair.Server.Port,
            pair.ServerFingerprint));
        var qr = PairingJson.ParseQrPayload(qrJson, out var qrError);
        Assert.Null(qrError);
        Assert.NotNull(qr);

        using var client = pair.CreatePinnedHttpClient();
        using var response = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody(ConfirmRequestJson(qr.Token)));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var confirm = PairingJson.ParseConfirmResponse(await response.Content.ReadAsStringAsync(), out var parseError);
        Assert.Null(parseError);
        Assert.NotNull(confirm);
        Assert.Equal(PeerPair.WindowsDeviceId, confirm.DeviceId);
        Assert.Equal("DESKTOP-WIN", confirm.DisplayName);

        // The scanner stores the listener with the secret and epoch from the response.
        Assert.True(ProtocolValidation.TryDecodeBase64Url256(confirm.PairSecret, out var secret));
        await pair.AndroidStore.UpsertDeviceWithEpochAsync(
            new NewPairedDevice(
                confirm.DeviceId,
                confirm.DisplayName,
                confirm.Platform,
                qr.CertSha256,
                Convert.ToBase64String(pair.Protector.Protect(secret))),
            confirm.TrustEpoch,
            DateTimeOffset.UtcNow);

        // The very next sync session authenticates and converges with the new pairing.
        await PeerPair.CaptureAsync(pair.WindowsStore, "paired hello");
        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("paired hello"));
        var result = await session.CloseAsync();
        Assert.True(result.Authenticated);
    }

    [Fact]
    public async Task RepairInvalidatesTheOldCredentials()
    {
        await using var pair = await PeerPair.CreateAsync(
            pairingApprover: new DelegateApprover((_, _) => Task.FromResult(true)));

        // The stage-2 style pairing works first: prove it by syncing one clip.
        await PeerPair.CaptureAsync(pair.WindowsStore, "repair-probe");
        var before = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("repair-probe"));
        var beforeResult = await before.CloseAsync();
        Assert.True(beforeResult.Authenticated);

        // Re-pair over HTTP: Windows now trusts a new secret at a higher epoch.
        using var client = pair.CreatePinnedHttpClient();
        var ticket = pair.Pairing!.IssueTicket();
        using var response = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody(ConfirmRequestJson(ticket.Token)));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var confirm = PairingJson.ParseConfirmResponse(await response.Content.ReadAsStringAsync(), out _);
        Assert.NotNull(confirm);
        Assert.True(confirm.TrustEpoch > 1);

        // The Android store still holds the old secret and epoch, like a stale APK would.
        var stale = await pair.DialAsync();
        var staleResult = await stale.Run.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.False(staleResult.Authenticated);
        Assert.Equal(ProtocolErrorCodes.TrustEpochMismatch, staleResult.ErrorCode);
    }

    [Fact]
    public async Task InvalidTokenAndRejectionReturnContractErrors()
    {
        var decisions = new Queue<bool>([false]);
        await using var pair = await PeerPair.CreateAsync(
            pairAndroidSide: false,
            pairWindowsSide: false,
            pairingApprover: new DelegateApprover((_, _) => Task.FromResult(decisions.TryDequeue(out var value) && value)));
        using var client = pair.CreatePinnedHttpClient();

        // Unknown token: no ticket outstanding yet.
        var madeUp = ProtocolValidation.EncodeBase64Url(new byte[32]);
        using var invalid = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody(ConfirmRequestJson(madeUp)));
        Assert.Equal(HttpStatusCode.Forbidden, invalid.StatusCode);
        var invalidBody = PairingJson.ParseError(await invalid.Content.ReadAsStringAsync(), out _);
        Assert.Equal(PairingErrorCodes.TokenInvalid, invalidBody!.Error);

        // The user declines: 403 with PAIRING_REJECTED, and nothing is stored.
        var ticket = pair.Pairing!.IssueTicket();
        using var rejected = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody(ConfirmRequestJson(ticket.Token)));
        Assert.Equal(HttpStatusCode.Forbidden, rejected.StatusCode);
        var rejectedBody = PairingJson.ParseError(await rejected.Content.ReadAsStringAsync(), out _);
        Assert.Equal(PairingErrorCodes.Rejected, rejectedBody!.Error);
        Assert.Null(await pair.WindowsStore.GetDeviceAsync(PeerPair.AndroidDeviceId));

        // The rejected token is burned: retrying is invalid, so a fresh QR is required.
        using var burned = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody(ConfirmRequestJson(ticket.Token)));
        Assert.Equal(HttpStatusCode.Forbidden, burned.StatusCode);
    }

    [Fact]
    public async Task MalformedOversizedAndUnversionedRequestsAreRejected()
    {
        await using var pair = await PeerPair.CreateAsync(
            pairingApprover: new DelegateApprover((_, _) => Task.FromResult(true)));
        using var client = pair.CreatePinnedHttpClient();

        using var malformed = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody("{\"kind\":\"pairing_confirm_request\""));
        Assert.Equal(HttpStatusCode.BadRequest, malformed.StatusCode);

        using var oversized = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody("{\"pad\":\"" + new string('x', PairingJson.MaxDocumentBytes) + "\"}"));
        Assert.Equal(HttpStatusCode.BadRequest, oversized.StatusCode);

        // Without the protocol version header, the shared gate answers first.
        using var bare = new HttpClient(new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, _, _, _) => true
        });
        using var unversioned = await bare.PostAsync(
            new Uri($"https://127.0.0.1:{pair.Server.Port}/v1/pair/confirm"),
            JsonBody(ConfirmRequestJson(ProtocolValidation.EncodeBase64Url(new byte[32]))));
        Assert.Equal(HttpStatusCode.BadRequest, unversioned.StatusCode);
        Assert.Contains("UNSUPPORTED_VERSION", await unversioned.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task PairingDisabledServerHasNoConfirmEndpoint()
    {
        await using var pair = await PeerPair.CreateAsync();
        using var client = pair.CreatePinnedHttpClient();
        using var response = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody(ConfirmRequestJson(ProtocolValidation.EncodeBase64Url(new byte[32]))));
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task PairingLogsNeverContainTokensOrSecrets()
    {
        await using var pair = await PeerPair.CreateAsync(
            pairAndroidSide: false,
            pairWindowsSide: false,
            pairingApprover: new DelegateApprover((_, _) => Task.FromResult(true)));
        var ticket = pair.Pairing!.IssueTicket();

        using var client = pair.CreatePinnedHttpClient();
        using var response = await client.PostAsync(
            new Uri("/v1/pair/confirm", UriKind.Relative),
            JsonBody(ConfirmRequestJson(ticket.Token)));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var confirm = PairingJson.ParseConfirmResponse(await response.Content.ReadAsStringAsync(), out _);

        Assert.NotEmpty(pair.Logs.Lines);
        foreach (var line in pair.Logs.Lines)
        {
            Assert.DoesNotContain(ticket.Token, line, StringComparison.Ordinal);
            Assert.DoesNotContain(confirm!.PairSecret, line, StringComparison.Ordinal);
        }
    }

    private static string ConfirmRequestJson(string token) =>
        PairingJson.Serialize(new PairingConfirmRequest
        {
            Kind = PairingDocumentKinds.ConfirmRequest,
            Version = 1,
            Token = token,
            DeviceId = PeerPair.AndroidDeviceId,
            DisplayName = "Pixel 8",
            Platform = "android"
        });

    private static StringContent JsonBody(string json) =>
        new(json, Encoding.UTF8, "application/json");
}
