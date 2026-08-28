using ClipSync.Core.Media;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;
using ClipSync.Peer.Transport;

namespace ClipSync.Tests.Peer;

/// <summary>
/// Scripted-dialer regression test for the duplicate clip_payload_begin guard: a second
/// begin for an event whose transfer is still open must fail the session with
/// MEDIA_OUT_OF_ORDER instead of silently replacing the open transfer, which leaked its
/// download slot and orphaned a locked half-written temp file. The real listener engine
/// runs behind the Kestrel endpoint; only the phone side is played by the test.
/// </summary>
public sealed class ImagePayloadDuplicateBeginTests
{
    [Fact]
    public async Task DuplicatePayloadBeginFailsTheSessionAndAbortsTheHalfOpenTransfer()
    {
        await using var pair = await PeerPair.CreateAsync();
        var transport = await ClipSync.Peer.Client.PeerSyncClient.ConnectAsync(
            "127.0.0.1",
            pair.Server.Port,
            pair.ServerFingerprint,
            ProtocolLimits.ProtocolVersionV2,
            CancellationToken.None);
        await using var _ = transport;
        await CompleteHandshakeAsync(pair, transport);

        // Announce one phone-origin image; the listener pulls the body with clip_fetch.
        var encoded = await File.ReadAllBytesAsync(
            Path.Combine(AppContext.BaseDirectory, "protocol-fixtures-v2", "media", "png-8x8.png"));
        var contentHash = ImageCodec.HashBytes(encoded);
        var eventId = Guid.NewGuid().ToString("D");
        await SendAsync(transport, ProtocolMessageTypes.ClipAnnounce, new ClipAnnounceBody
        {
            Clips =
            [
                new ClipHeaderDto
                {
                    EventId = eventId,
                    OriginDeviceId = PeerPair.AndroidDeviceId,
                    OriginSeq = 1,
                    Availability = ClipAvailability.Available,
                    Kind = "image",
                    ContentHash = contentHash,
                    SourceApp = "test",
                    CreatedAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    MimeType = MediaLimits.MimePng,
                    EncodedBytes = encoded.Length,
                    PixelWidth = 8,
                    PixelHeight = 8
                }
            ]
        });
        var fetch = (ClipFetchBody)(await AwaitTypeAsync(transport, ProtocolMessageTypes.ClipFetch)).Body;
        Assert.Equal([eventId], fetch.EventIds);

        // The first begin declares two chunks but only chunk 0 arrives: the transfer stays
        // open, holding one download slot and a half-written temp file on disk.
        var firstTransferId = Guid.NewGuid().ToString("D");
        const int chunk0Bytes = 41;
        await SendAsync(transport, ProtocolMessageTypes.ClipPayloadBegin, new ClipPayloadBeginBody
        {
            TransferId = firstTransferId,
            EventId = eventId,
            ChunkCount = 2,
            EncodedBytes = encoded.Length,
            ContentHash = contentHash,
            MimeType = MediaLimits.MimePng
        });
        await SendAsync(transport, ProtocolMessageTypes.ClipPayloadChunk, new ClipPayloadChunkBody
        {
            TransferId = firstTransferId,
            EventId = eventId,
            ChunkIndex = 0,
            ChunkCount = 2,
            ChunkBytes = chunk0Bytes,
            Data = ProtocolValidation.EncodeBase64Url(encoded.AsSpan(0, chunk0Bytes))
        });
        var temps = Path.Combine(pair.WindowsStore.Media.RootDirectory, MediaBlobStore.TempDirectoryName);
        await pair.WaitUntilAsync(() => Task.FromResult(Directory.GetFiles(temps).Length == 1));

        // The duplicate begin (fresh transfer id, same event) must be refused, not swallowed.
        await SendAsync(transport, ProtocolMessageTypes.ClipPayloadBegin, new ClipPayloadBeginBody
        {
            TransferId = Guid.NewGuid().ToString("D"),
            EventId = eventId,
            ChunkCount = 2,
            EncodedBytes = encoded.Length,
            ContentHash = contentHash,
            MimeType = MediaLimits.MimePng
        });

        var sawMediaOutOfOrder = false;
        for (var reads = 0; reads < 16; reads++)
        {
            var frame = await ReceiveWithTimeoutAsync(transport);
            if (frame is TransportFrame.Text text
                && text.Payload.Contains(ProtocolErrorCodes.MediaOutOfOrder, StringComparison.Ordinal)
                && text.Payload.Contains("\"retryable\":false", StringComparison.Ordinal))
            {
                sawMediaOutOfOrder = true;
            }

            if (frame is TransportFrame.Closed)
            {
                break;
            }
        }

        Assert.True(sawMediaOutOfOrder, "expected a fatal MEDIA_OUT_OF_ORDER error frame before close");

        // Session-end cleanup aborted the open transfer: the temp file is gone (nothing is
        // left holding it open) and no image event was ever committed.
        await pair.WaitUntilAsync(() => Task.FromResult(Directory.GetFiles(temps).Length == 0));
        Assert.False(await pair.WindowsStore.FindLiveBlobByHashAsync(contentHash, CancellationToken.None));
    }

    /// <summary>Hello → challenge → auth → known_vector exchange, as the Android dialer plays it.</summary>
    private static async Task CompleteHandshakeAsync(PeerPair pair, ISyncTransport transport)
    {
        var device = await pair.WindowsStore.GetDeviceAsync(PeerPair.AndroidDeviceId);
        Assert.NotNull(device);
        await SendAsync(transport, ProtocolMessageTypes.Hello, new HelloBody
        {
            DeviceId = PeerPair.AndroidDeviceId,
            Platform = "android",
            ClientVersion = "0.2.0",
            TrustEpoch = device!.TrustEpoch,
            KnownVector = new SyncStateDto { Origins = [] },
            Capabilities = [ProtocolLimits.CapabilityImageClipV2]
        });

        var challenge = await AwaitTypeAsync(transport, ProtocolMessageTypes.Challenge);
        var challengeBody = (ChallengeBody)challenge.Body;
        Assert.True(ProtocolValidation.TryDecodeBase64Url256(challengeBody.Nonce, out var nonce));
        var proof = PairAuthProof.Compute(
            pair.PairSecret,
            challenge.RequestId,
            nonce,
            Guid.Parse(PeerPair.WindowsDeviceId),
            Guid.Parse(PeerPair.AndroidDeviceId),
            challengeBody.TrustEpoch,
            ProtocolLimits.ProtocolVersionV2);
        await SendAsync(transport, ProtocolMessageTypes.Auth, new AuthBody
        {
            Algorithm = ProtocolValidation.HmacSha256,
            ChallengeRequestId = challenge.RequestId.ToString("D"),
            ResponderDeviceId = PeerPair.AndroidDeviceId,
            TrustEpoch = challengeBody.TrustEpoch,
            Proof = ProtocolValidation.EncodeBase64Url(proof)
        });

        // The listener enters ready and sends its vector; reply with an empty one so it
        // neither wants nor announces anything before the scripted image exchange.
        await AwaitTypeAsync(transport, ProtocolMessageTypes.KnownVector);
        await SendAsync(transport, ProtocolMessageTypes.KnownVector, new SyncStateDto { Origins = [] });
    }

    private static async Task SendAsync(ISyncTransport transport, string type, object body) =>
        await transport.SendTextAsync(
            ProtocolWriter.Serialize(ProtocolLimits.ProtocolVersionV2, type, Guid.NewGuid(), body),
            CancellationToken.None);

    /// <summary>Next frame of the given type; heartbeat pings in between are skipped.</summary>
    private static async Task<ProtocolParseOutcome.Success> AwaitTypeAsync(ISyncTransport transport, string type)
    {
        for (var reads = 0; reads < 16; reads++)
        {
            var frame = await ReceiveWithTimeoutAsync(transport);
            var text = Assert.IsType<TransportFrame.Text>(frame);
            var parsed = Assert.IsType<ProtocolParseOutcome.Success>(ProtocolReaderV2.Parse(text.Payload));
            if (parsed.Type == ProtocolMessageTypes.Ping && type != ProtocolMessageTypes.Ping)
            {
                continue;
            }

            Assert.Equal(type, parsed.Type);
            return parsed;
        }

        throw new InvalidOperationException($"never received a {type} frame");
    }

    private static async Task<TransportFrame> ReceiveWithTimeoutAsync(ISyncTransport transport)
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        return await transport.ReceiveAsync(cts.Token);
    }
}
