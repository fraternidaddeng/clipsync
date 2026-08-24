using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;

namespace ClipSync.Tests.Peer;

/// <summary>
/// Pre-auth admission at the listener: the per-remote-address sliding-window rate limit
/// answers 429 before the WebSocket upgrade, and the suspend gate answers 503 while the
/// machine is heading into sleep.
/// </summary>
public sealed class PeerServerAdmissionTests
{
    [Fact]
    public async Task SyncAcceptsAreRateLimitedPerRemoteAddress()
    {
        await using var pair = await PeerPair.CreateAsync(maxSyncAcceptsPerWindow: 2);

        Assert.Null(await TryDialAsync(pair));
        Assert.Null(await TryDialAsync(pair));

        // The two accepted dials used up the loopback budget; the third is refused
        // before the socket upgrade.
        Assert.Equal(HttpStatusCode.TooManyRequests, await TryDialAsync(pair));
        Assert.Contains(pair.Logs.Lines, line => line.Contains("rate limited kind=sync_accept", StringComparison.Ordinal));
    }

    [Fact]
    public async Task SuspendGateRefusesNewSessionsAndReopensOnDemand()
    {
        await using var pair = await PeerPair.CreateAsync();
        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(() => Task.FromResult(pair.Server.ConnectedDeviceCount == 1));

        // Entering the suspend window: refuse new sessions first, then drop the live one.
        pair.Server.SetRefuseNewSessions(true);
        pair.Server.DisconnectAllSessions();
        await session.Run.WaitAsync(TimeSpan.FromSeconds(10));
        await pair.WaitUntilAsync(() => Task.FromResult(pair.Server.ActiveSessionCount == 0));

        Assert.Equal(HttpStatusCode.ServiceUnavailable, await TryDialAsync(pair));

        // Resume: the gate reopens and the same listener accepts again.
        pair.Server.SetRefuseNewSessions(false);
        Assert.Null(await TryDialAsync(pair));
    }

    /// <summary>Dials the sync endpoint; null means the upgrade was accepted.</summary>
    private static async Task<HttpStatusCode?> TryDialAsync(PeerPair pair)
    {
        using var socket = new ClientWebSocket();
        socket.Options.CollectHttpResponseDetails = true;
        socket.Options.SetRequestHeader("X-Protocol-Version", "1");
        socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
            certificate is not null
            && string.Equals(
                Convert.ToHexString(SHA256.HashData(certificate.GetRawCertData())).ToLowerInvariant(),
                pair.ServerFingerprint,
                StringComparison.Ordinal);
        try
        {
            await socket.ConnectAsync(
                new Uri($"wss://127.0.0.1:{pair.Server.Port}/v1/peer/sync"),
                CancellationToken.None);
            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None);
            return null;
        }
        catch (WebSocketException)
        {
            return socket.HttpStatusCode;
        }
    }
}
