using System.Net.WebSockets;
using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Core.Sync;
using ClipSync.Peer.Server;
using ClipSync.Peer.Sessions;

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
    public async Task ServerTracksConnectedDevicesAndRaisesSessionsChanged()
    {
        await using var pair = await PeerPair.CreateAsync();
        var changes = 0;
        pair.Server.SessionsChanged += () => Interlocked.Increment(ref changes);

        Assert.Equal(0, pair.Server.ConnectedDeviceCount);
        Assert.Empty(pair.Server.ConnectedDeviceIds);

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(() => Task.FromResult(pair.Server.ConnectedDeviceCount == 1));
        Assert.Equal([PeerPair.AndroidDeviceId], pair.Server.ConnectedDeviceIds);
        // ConnectedDeviceCount flips with Engine.IsReady, while SessionsChanged is raised
        // separately on a worker thread — the count condition above can be observed before
        // the handler runs, so the event itself must be awaited, not asserted.
        await pair.WaitUntilAsync(() => Task.FromResult(Volatile.Read(ref changes) >= 1));

        await session.CloseAsync();
        await pair.WaitUntilAsync(() => Task.FromResult(pair.Server.ConnectedDeviceCount == 0));
        await pair.WaitUntilAsync(() => Task.FromResult(Volatile.Read(ref changes) >= 2));
    }

    [Fact]
    public async Task OutboxStatusDrainsToZeroWithAnAckTimestampAfterALiveSession()
    {
        // The conduit's local-service segment reads exactly this snapshot via
        // MainViewModel.RefreshOutboxAsync: queue depth and the last peer-ack time.
        await using var pair = await PeerPair.CreateAsync();
        await PeerPair.CaptureAsync(pair.WindowsStore, "conduit-queued-1");
        await PeerPair.CaptureAsync(pair.WindowsStore, "conduit-queued-2");

        var queued = await pair.WindowsStore.GetOutboxStatusAsync();
        Assert.Equal(2, queued.PendingCount);
        Assert.Null(queued.LastPeerAckAt);

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await pair.WindowsStore.GetOutboxStatusAsync()).PendingCount == 0);

        // Both rows were confirmed by the phone, and the confirmation time is recorded.
        var drained = await pair.WindowsStore.GetOutboxStatusAsync();
        Assert.Equal(0, drained.PendingCount);
        Assert.NotNull(drained.LastPeerAckAt);
        Assert.InRange(
            drained.LastPeerAckAt!.Value,
            DateTimeOffset.UtcNow.AddMinutes(-5),
            DateTimeOffset.UtcNow.AddMinutes(1));

        await session.CloseAsync();
    }

    [Fact]
    public async Task ListenerRaisesRemoteClipsCommittedAndBumpsLastSeenForPhonePushes()
    {
        // The app layer feeds MainViewModel.NotifyRemoteActivityAsync (device rows, history
        // origin badges) and the auto-apply path from this server event, so it must fire
        // with the pushed content and the true origin device.
        await using var pair = await PeerPair.CreateAsync();
        var committed = new List<RemoteClipApplied>();
        pair.Server.RemoteClipsCommitted += batch =>
        {
            lock (committed)
            {
                committed.AddRange(batch);
            }
        };

        Assert.Null((await pair.WindowsStore.GetDeviceAsync(PeerPair.AndroidDeviceId))!.LastSeenAt);

        await PeerPair.CaptureAsync(pair.AndroidStore, "phone-push");
        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.WindowsStore)).Contains("phone-push"));

        lock (committed)
        {
            var applied = Assert.Single(committed);
            Assert.Equal("phone-push", applied.Content);
            Assert.Equal(PeerPair.AndroidDeviceId, applied.OriginDeviceId);
        }

        // The listener stamped last-seen during the handshake: the conduit device row can
        // leave "Never connected" without waiting for content to flow.
        var device = await pair.WindowsStore.GetDeviceAsync(PeerPair.AndroidDeviceId);
        Assert.NotNull(device!.LastSeenAt);

        await session.CloseAsync();
    }

    [Fact]
    public async Task ConnectedDeviceSnapshotFollowsTheFullSessionLifecycle()
    {
        // The conduit network segment renders UpdatePeerStatus(online, port, connectedCount);
        // this drives the same snapshot through connect, converge, and disconnect.
        await using var pair = await PeerPair.CreateAsync();
        var changes = 0;
        pair.Server.SessionsChanged += () => Interlocked.Increment(ref changes);

        Assert.True(pair.Server.Port > 0);
        Assert.Equal(0, pair.Server.ConnectedDeviceCount);

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(() => Task.FromResult(pair.Server.ConnectedDeviceCount == 1));
        Assert.Equal([PeerPair.AndroidDeviceId], pair.Server.ConnectedDeviceIds);

        // A second dial from the same device must not double-count the conduit's device tally.
        var second = await pair.DialAsync();
        await pair.WaitUntilAsync(() => Task.FromResult(second.Engine.IsReady));
        Assert.Equal(1, pair.Server.ConnectedDeviceCount);

        await second.CloseAsync();
        await session.CloseAsync();
        await pair.WaitUntilAsync(() => Task.FromResult(pair.Server.ConnectedDeviceCount == 0));
        Assert.True(Volatile.Read(ref changes) >= 2);
    }

    [Fact]
    public async Task DialerRaisesSessionReadyWithThePeerDeviceId()
    {
        await using var pair = await PeerPair.CreateAsync();

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(() =>
        {
            lock (session.ReadyPeers)
            {
                return Task.FromResult(session.ReadyPeers.Count > 0);
            }
        });
        lock (session.ReadyPeers)
        {
            Assert.Equal([PeerPair.WindowsDeviceId], session.ReadyPeers);
        }

        Assert.True(session.Engine.IsReady);
        await session.CloseAsync();
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
    public async Task DeletedAfterAckTravelsAsTombstone()
    {
        await using var pair = await PeerPair.CreateAsync();
        var stored = await PeerPair.CaptureAsync(pair.WindowsStore, "later-deleted");
        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("later-deleted"));
        await session.CloseAsync();

        Assert.True(await pair.WindowsStore.DeleteAsync(stored.EventId, DateTimeOffset.UtcNow));

        session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            !(await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("later-deleted"));
        await session.CloseAsync();
    }

    [Fact]
    public async Task ImageClipTravelsOverV2WithBytesIntact()
    {
        await using var pair = await PeerPair.CreateAsync();
        var png = ClipSync.Core.Media.ImageCodec.EncodePngBgra(2, 1, [255, 0, 0, 255, 0, 255, 0, 255]);
        var hash = ClipSync.Core.Media.ImageCodec.HashBytes(png);
        await PeerPair.CaptureImageAsync(pair.WindowsStore, png, hash, "image/png", width: 2, height: 1);

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
        {
            var items = await pair.AndroidStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
            return items.Any(item => item.IsImage && item.ContentHash == hash);
        });

        // The chunked v2 transfer reassembled the exact encoded bytes, content-addressed.
        Assert.True(pair.AndroidStore.Media.Exists(hash));
        Assert.Equal(png, pair.AndroidStore.Media.ReadAllBytes(hash));
        var entry = (await pair.AndroidStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50)))
            .Single(item => item.IsImage);
        Assert.Equal("image/png", entry.MimeType);
        Assert.Equal(2, entry.PixelWidth);
        Assert.Equal(1, entry.PixelHeight);
        Assert.Equal(png.Length, entry.EncodedBytes);

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
        // No apply-state delegate wired: the field stays off the wire entirely, so peers
        // read the absence as "not reported" instead of a fabricated posture.
        Assert.DoesNotContain("clipboard_apply_text", body, StringComparison.Ordinal);
    }

    [Fact]
    public async Task HealthEndpointReportsTheLiveClipboardApplyPosture()
    {
        // The phone's 对端写入 conduit segment reads exactly this self-report; QA 2026-08-25
        // found it stuck on 未探测 because the field never existed. The delegate must be
        // re-read per request so a posture toggle (pause, 自动写入 off) applies immediately.
        var applyState = ClipboardApplyStates.Unverified;
        await using var pair = await PeerPair.CreateAsync(
            clipboardApplyState: () => Volatile.Read(ref applyState));
        using var client = pair.CreatePinnedHttpClient();

        var first = await client.GetStringAsync("/v1/peer/health");
        Assert.Contains("\"clipboard_apply_text\":\"unverified\"", first, StringComparison.Ordinal);

        // A real remote apply succeeded; the very next probe must say so.
        Volatile.Write(ref applyState, ClipboardApplyStates.Applied);
        var afterApply = await client.GetStringAsync("/v1/peer/health");
        Assert.Contains("\"clipboard_apply_text\":\"applied\"", afterApply, StringComparison.Ordinal);

        // The user paused sync; posture outranks stale evidence on the next probe.
        Volatile.Write(ref applyState, ClipboardApplyStates.Paused);
        var afterPause = await client.GetStringAsync("/v1/peer/health");
        Assert.Contains("\"clipboard_apply_text\":\"paused\"", afterPause, StringComparison.Ordinal);
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
    public async Task PausedListenerHoldsOutboundContentAndReleasesItOnResume()
    {
        // Mirrors the Android engine's outboundAllowed gate test: 一键暂停 must stop
        // outbound content immediately at every serving path, while inbound and the
        // already-persisted queue stay intact.
        var outboundAllowed = true;
        await using var pair = await PeerPair.CreateAsync(
            serverSessionOptions: PeerPair.DefaultSessionOptions() with
            {
                OutboundAllowed = () => Volatile.Read(ref outboundAllowed)
            });

        await PeerPair.CaptureAsync(pair.WindowsStore, "before-pause");
        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("before-pause"));

        // Pause on the Windows side: the new capture is neither announced by the outbox
        // drain nor served through want_ranges while the gate is closed.
        Volatile.Write(ref outboundAllowed, false);
        await PeerPair.CaptureAsync(pair.WindowsStore, "while-paused");
        await Task.Delay(500); // several 100 ms drain ticks
        Assert.DoesNotContain("while-paused", await PeerPair.VisibleTextsAsync(pair.AndroidStore));

        // The entry stayed pending instead of being announced into the void.
        Assert.True((await pair.WindowsStore.GetOutboxStatusAsync()).PendingCount >= 1);

        // Inbound is untouched by the pause: the phone's clip still reaches Windows history.
        await PeerPair.CaptureAsync(pair.AndroidStore, "phone-clip");
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.WindowsStore)).Contains("phone-clip"));
        Assert.DoesNotContain("while-paused", await PeerPair.VisibleTextsAsync(pair.AndroidStore));

        // Resume: the pending entry flows on the next drain tick; nothing was lost.
        Volatile.Write(ref outboundAllowed, true);
        await pair.WaitUntilAsync(async () =>
            (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("while-paused"));

        await session.CloseAsync();
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

    [Fact]
    public async Task FrameFloodClosesTheSessionWithRetryableRateLimited()
    {
        // Stage-6 hardening W5: the per-session frame budget must stop a tight frame loop
        // even though every frame is individually valid (pings are answered pre-auth).
        await using var pair = await PeerPair.CreateAsync(
            serverSessionOptions: PeerPair.DefaultSessionOptions() with { MaxFramesPerRateWindow = 5 });
        var transport = await ClipSync.Peer.Client.PeerSyncClient.ConnectAsync(
            "127.0.0.1",
            pair.Server.Port,
            pair.ServerFingerprint,
            CancellationToken.None);
        await using var _ = transport;

        for (var frame = 0; frame < 8; frame++)
        {
            var json = ProtocolWriter.Serialize(
                ProtocolMessageTypes.Ping,
                Guid.NewGuid(),
                new PingBody { SentAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });
            await transport.SendTextAsync(json, CancellationToken.None);
        }

        // Expect budget-many pongs, then one retryable RATE_LIMITED error, then the close.
        var sawRetryableRateLimited = false;
        for (var reads = 0; reads < 16; reads++)
        {
            var frame = await ReceiveWithTimeoutAsync(transport);
            if (frame is ClipSync.Peer.Transport.TransportFrame.Text text
                && text.Payload.Contains(ProtocolErrorCodes.RateLimited, StringComparison.Ordinal)
                && text.Payload.Contains("\"retryable\":true", StringComparison.Ordinal))
            {
                sawRetryableRateLimited = true;
            }

            if (frame is ClipSync.Peer.Transport.TransportFrame.Closed)
            {
                break;
            }
        }

        Assert.True(sawRetryableRateLimited, "expected a retryable RATE_LIMITED error frame before close");
    }

    private static async Task<ClipSync.Peer.Transport.TransportFrame> ReceiveWithTimeoutAsync(
        ClipSync.Peer.Transport.ISyncTransport transport)
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        return await transport.ReceiveAsync(cts.Token);
    }
}
