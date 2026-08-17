using System.Net.WebSockets;
using System.Text;
using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Core.Sync;

namespace ClipSync.Tests.Peer;

public sealed class PeerSyncIntegrationTests
{
    [Fact]
    public async Task TwoWayConvergenceDeliversEverythingExactlyOnce()
    {
        await using var pair = await PeerPair.CreateAsync();
        await PeerPair.CaptureAsync(pair.WindowsStore, "win-1");
        await PeerPair.CaptureAsync(pair.WindowsStore, "win-2");
        await PeerPair.CaptureAsync(pair.AndroidStore, "droid-1");

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
        {
            var windows = await PeerPair.VisibleTextsAsync(pair.WindowsStore);
            var android = await PeerPair.VisibleTextsAsync(pair.AndroidStore);
            return windows.ToHashSet().SetEquals(["win-1", "win-2", "droid-1"])
                && android.ToHashSet().SetEquals(["win-1", "win-2", "droid-1"]);
        });

        // Exactly once: no duplicate rows anywhere.
        Assert.Equal(3, (await PeerPair.VisibleTextsAsync(pair.WindowsStore)).Count);
        Assert.Equal(3, (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Count);

        // The dialer surfaced the two Windows bodies for auto-apply, in commit order.
        lock (session.Committed)
        {
            Assert.Equal(["win-1", "win-2"], session.Committed.Select(item => item.Content));
        }

        var result = await session.CloseAsync();
        Assert.True(result.Authenticated);
    }

    [Fact]
    public async Task OfflineCapturesArriveAfterReconnectWithoutDuplicates()
    {
        await using var pair = await PeerPair.CreateAsync();
        await PeerPair.CaptureAsync(pair.WindowsStore, "before-disconnect");

        var first = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("before-disconnect"));
        await first.CloseAsync();

        // Captured while the peer is offline; only the outbox remembers.
        await PeerPair.CaptureAsync(pair.WindowsStore, "offline-1");
        await PeerPair.CaptureAsync(pair.WindowsStore, "offline-2");

        var second = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
        {
            var android = await PeerPair.VisibleTextsAsync(pair.AndroidStore);
            return android.Contains("offline-1") && android.Contains("offline-2");
        });

        var texts = await PeerPair.VisibleTextsAsync(pair.AndroidStore);
        Assert.Equal(3, texts.Count);
        await second.CloseAsync();
    }

    [Fact]
    public async Task WrongSecretFailsAuthAndRepeatsGetThrottled()
    {
        await using var pair = await PeerPair.CreateAsync(useDifferentAndroidSecret: true);

        for (var attempt = 0; attempt < 5; attempt++)
        {
            var failed = await pair.DialAsync();
            var result = await failed.Run.WaitAsync(TimeSpan.FromSeconds(10));
            Assert.Equal(ProtocolErrorCodes.AuthFailed, result.ErrorCode);
            Assert.False(result.Authenticated);
        }

        var throttled = await pair.DialAsync();
        var throttledResult = await throttled.Run.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(ProtocolErrorCodes.RateLimited, throttledResult.ErrorCode);
    }

    [Fact]
    public async Task UnknownDeviceIsRejected()
    {
        await using var pair = await PeerPair.CreateAsync(pairWindowsSide: false);
        var session = await pair.DialAsync();
        var result = await session.Run.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(ProtocolErrorCodes.AuthFailed, result.ErrorCode);
    }

    [Fact]
    public async Task TrustEpochMismatchIsRejected()
    {
        await using var pair = await PeerPair.CreateAsync(extraWindowsUpserts: 1);
        var session = await pair.DialAsync();
        var result = await session.Run.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(ProtocolErrorCodes.TrustEpochMismatch, result.ErrorCode);
    }

    [Fact]
    public async Task RevocationDropsLiveSessionAndBlocksNewOnes()
    {
        await using var pair = await PeerPair.CreateAsync();
        var session = await pair.DialAsync();
        await PeerPair.CaptureAsync(pair.WindowsStore, "pre-revoke");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("pre-revoke"));

        Assert.True(await pair.WindowsStore.RevokeDeviceAsync(PeerPair.AndroidDeviceId, DateTimeOffset.UtcNow));
        pair.Server.DisconnectDevice(PeerPair.AndroidDeviceId);
        await session.Run.WaitAsync(TimeSpan.FromSeconds(10));

        var rejected = await pair.DialAsync();
        var result = await rejected.Run.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(ProtocolErrorCodes.DeviceRevoked, result.ErrorCode);
    }

    [Fact]
    public async Task DeletedClipTravelsAsTerminalMarkerWithoutContent()
    {
        await using var pair = await PeerPair.CreateAsync();
        var stored = await PeerPair.CaptureAsync(pair.WindowsStore, "vanishing-secret");
        Assert.True(await pair.WindowsStore.DeleteAsync(stored.EventId, DateTimeOffset.UtcNow));

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
        {
            var vector = await pair.AndroidStore.GetKnownVectorAsync();
            return vector.TryGetValue(PeerPair.WindowsDeviceId, out var state)
                && state.ContiguousSeq >= stored.OriginSequence;
        });

        Assert.DoesNotContain("vanishing-secret", await PeerPair.VisibleTextsAsync(pair.AndroidStore));
        await session.CloseAsync();
    }

    [Fact]
    public async Task BacklogIsServedThroughWantRangesInCappedRounds()
    {
        await using var pair = await PeerPair.CreateAsync();
        for (var index = 1; index <= 7; index++)
        {
            await PeerPair.CaptureAsync(pair.WindowsStore, $"backlog-{index}");
        }

        // Simulate lost acknowledgments: the outbox is empty, so only want_ranges can serve the backlog.
        await pair.WindowsStore.ApplyPeerAckRangesAsync(
            PeerPair.AndroidDeviceId,
            [new OriginSequenceRanges(PeerPair.WindowsDeviceId, [new SequenceRange(1, 7)])],
            DateTimeOffset.UtcNow);
        Assert.Empty(await pair.WindowsStore.GetOutboxBatchAsync(PeerPair.AndroidDeviceId, 10));

        var session = await pair.DialAsync(PeerPair.DialerOptions() with { WantSequencesPerOrigin = 3 });
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Count == 7);
        await session.CloseAsync();
    }

    [Fact]
    public async Task OversizedWantRangesGetsRetryableRateLimitAndSyncStillConverges()
    {
        await using var pair = await PeerPair.CreateAsync(
            serverSessionOptions: PeerPair.DefaultSessionOptions() with { MaxRequestedSequencesPerMessage = 5 });
        for (var index = 1; index <= 8; index++)
        {
            await PeerPair.CaptureAsync(pair.WindowsStore, $"bulk-{index}");
        }

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Count == 8);

        // The oversized want was refused as retryable, and the session survived it.
        Assert.Contains(pair.Logs.Lines, line => line.Contains(ProtocolErrorCodes.RateLimited, StringComparison.Ordinal));
        var result = await session.CloseAsync();
        Assert.True(result.Authenticated);
    }

    [Fact]
    public async Task LogsNeverContainContentSecretsOrProofs()
    {
        await using var pair = await PeerPair.CreateAsync();
        const string sentinel = "TOP-SECRET-CLIP-XYZZY";
        await PeerPair.CaptureAsync(pair.WindowsStore, sentinel);

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains(sentinel));
        await session.CloseAsync();

        var secretBase64 = Convert.ToBase64String(pair.PairSecret);
        var secretHex = Convert.ToHexString(pair.PairSecret);
        Assert.NotEmpty(pair.Logs.Lines);
        foreach (var line in pair.Logs.Lines)
        {
            Assert.DoesNotContain(sentinel, line, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain(secretBase64, line, StringComparison.Ordinal);
            Assert.DoesNotContain(secretHex, line, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("\"proof\"", line, StringComparison.Ordinal);
            Assert.DoesNotContain("\"nonce\"", line, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task HealthEndpointAnswersOnlyWithVersionHeader()
    {
        await using var pair = await PeerPair.CreateAsync();
        using var handler = new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
                certificate is not null
                && string.Equals(
                    Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(certificate.RawData)).ToLowerInvariant(),
                    pair.ServerFingerprint,
                    StringComparison.Ordinal)
        };
        using var client = new HttpClient(handler);
        var baseUri = new Uri($"https://127.0.0.1:{pair.Server.Port}");

        using var missingVersion = await client.GetAsync(new Uri(baseUri, "/v1/peer/health"));
        Assert.Equal(System.Net.HttpStatusCode.BadRequest, missingVersion.StatusCode);
        Assert.Contains(ProtocolErrorCodes.UnsupportedVersion, await missingVersion.Content.ReadAsStringAsync());

        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(baseUri, "/v1/peer/health"));
        request.Headers.Add("X-Protocol-Version", "1");
        using var healthy = await client.SendAsync(request);
        Assert.Equal(System.Net.HttpStatusCode.OK, healthy.StatusCode);
        var body = await healthy.Content.ReadAsStringAsync();
        Assert.Contains(PeerPair.WindowsDeviceId, body);
        Assert.DoesNotContain("secret", body, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task WrongCertificatePinBlocksTheConnection()
    {
        await using var pair = await PeerPair.CreateAsync();
        var wrongPin = string.Concat(pair.ServerFingerprint.Reverse());
        await Assert.ThrowsAnyAsync<WebSocketException>(() =>
            ClipSync.Peer.Client.PeerSyncClient.ConnectAsync("127.0.0.1", pair.Server.Port, wrongPin, CancellationToken.None));
    }

    [Fact]
    public async Task WrongProtocolVersionHeaderIsRejectedBeforeUpgrade()
    {
        await using var pair = await PeerPair.CreateAsync();
        using var socket = new ClientWebSocket();
        socket.Options.SetRequestHeader("X-Protocol-Version", "2");
        socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
            certificate is not null
            && string.Equals(
                Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(certificate.GetRawCertData())).ToLowerInvariant(),
                pair.ServerFingerprint,
                StringComparison.Ordinal);
        await Assert.ThrowsAnyAsync<WebSocketException>(() =>
            socket.ConnectAsync(new Uri($"wss://127.0.0.1:{pair.Server.Port}/v1/peer/sync"), CancellationToken.None));
    }

    [Fact]
    public async Task OversizedTextFrameIsRejectedWithPayloadTooLarge()
    {
        await using var pair = await PeerPair.CreateAsync();
        var transport = await ClipSync.Peer.Client.PeerSyncClient.ConnectAsync(
            "127.0.0.1",
            pair.Server.Port,
            pair.ServerFingerprint,
            CancellationToken.None);
        await using var _ = transport;

        var oversized = new string('x', ProtocolLimits.MaxWebSocketTextMessageBytes + 16);
        var sendTask = Task.Run(async () =>
        {
            try
            {
                await transport.SendTextAsync(oversized, CancellationToken.None);
            }
            catch (Exception)
            {
                // The server may abort mid-send once it sees the frame is oversized.
            }
        });

        var sawPayloadTooLarge = false;
        for (var reads = 0; reads < 5; reads++)
        {
            var frame = await ReceiveWithTimeoutAsync(transport);
            if (frame is ClipSync.Peer.Transport.TransportFrame.Text text
                && text.Payload.Contains(ProtocolErrorCodes.PayloadTooLarge, StringComparison.Ordinal))
            {
                sawPayloadTooLarge = true;
            }

            if (frame is ClipSync.Peer.Transport.TransportFrame.Closed)
            {
                break;
            }
        }

        Assert.True(sawPayloadTooLarge, "expected a PAYLOAD_TOO_LARGE error frame before close");
        await sendTask.WaitAsync(TimeSpan.FromSeconds(10));
    }

    private static async Task<ClipSync.Peer.Transport.TransportFrame> ReceiveWithTimeoutAsync(
        ClipSync.Peer.Transport.ISyncTransport transport)
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        return await transport.ReceiveAsync(cts.Token);
    }
}
