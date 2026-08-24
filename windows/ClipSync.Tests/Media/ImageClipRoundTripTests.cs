using System.Text.Json;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;

namespace ClipSync.Tests.Media;

/// <summary>
/// Cross-platform contract for the v2 image clip round-trip, driven by the small binary
/// fixtures in protocol/v2/fixtures/media. The Android suite runs the same assertions
/// (media/ImageClipRoundTripTest.kt) against the same files and manifest, so both stacks
/// must agree on bytes, hashes, chunk boundaries, and wire frames.
/// </summary>
public sealed class ImageClipRoundTripTests
{
    private static string MediaRoot => Path.Combine(AppContext.BaseDirectory, "protocol-fixtures-v2", "media");

    private static string ValidRoot => Path.Combine(AppContext.BaseDirectory, "protocol-fixtures-v2", "valid");

    public static IEnumerable<object[]> MediaFixtures()
    {
        yield return new object[] { "png-1x1-transparent.png", "png_1x1", MediaLimits.MimePng, 1, 1 };
        yield return new object[] { "png-2x2-quadrant.png", "png_2x2", MediaLimits.MimePng, 2, 2 };
        yield return new object[] { "png-8x8.png", "png_8x8", MediaLimits.MimePng, 8, 8 };
        yield return new object[] { "jpeg-1x1.jpg", "jpeg_1x1", MediaLimits.MimeJpeg, 1, 1 };
    }

    [Theory]
    [MemberData(nameof(MediaFixtures))]
    public void MediaFixtureInspectsToItsManifestEntry(string fileName, string manifestPrefix, string mime, int width, int height)
    {
        var encoded = File.ReadAllBytes(Path.Combine(MediaRoot, fileName));
        var manifest = Manifest();

        Assert.Equal(manifest.GetProperty($"{manifestPrefix}_bytes").GetInt32(), encoded.Length);

        var error = ImageCodec.TryInspect(encoded, out var image);
        Assert.Equal(ImageCodecError.Ok, error);
        Assert.NotNull(image);
        Assert.Equal(mime, image!.MimeType);
        Assert.Equal(width, image.PixelWidth);
        Assert.Equal(height, image.PixelHeight);
        Assert.Equal(encoded.Length, image.EncodedBytes);
        Assert.Equal(manifest.GetProperty($"{manifestPrefix}_sha256").GetString(), image.ContentHash);
    }

    [Theory]
    [MemberData(nameof(MediaFixtures))]
    public void MediaFixtureSurvivesChunkedWireRoundTripIntoTheBlobStore(string fileName, string manifestPrefix, string mime, int width, int height)
    {
        _ = manifestPrefix;
        _ = width;
        _ = height;
        var encoded = File.ReadAllBytes(Path.Combine(MediaRoot, fileName));
        var contentHash = ImageCodec.HashBytes(encoded);
        var transferId = Guid.NewGuid().ToString("D");
        var eventId = Guid.NewGuid().ToString("D");

        // Sender: split and serialize begin -> chunk* -> end exactly as the session engine does.
        var chunks = ImageChunks.Split(encoded);
        var frames = new List<string>
        {
            ProtocolWriter.Serialize(2, ProtocolMessageTypes.ClipPayloadBegin, Guid.NewGuid(), new ClipPayloadBeginBody
            {
                TransferId = transferId,
                EventId = eventId,
                ChunkCount = chunks.Count,
                EncodedBytes = encoded.Length,
                ContentHash = contentHash,
                MimeType = mime,
            }),
        };
        frames.AddRange(chunks.Select(chunk =>
            ProtocolWriter.Serialize(2, ProtocolMessageTypes.ClipPayloadChunk, Guid.NewGuid(), new ClipPayloadChunkBody
            {
                TransferId = transferId,
                EventId = eventId,
                ChunkIndex = chunk.Index,
                ChunkCount = chunk.Count,
                ChunkBytes = chunk.ByteCount,
                Data = chunk.Data,
            })));
        frames.Add(ProtocolWriter.Serialize(2, ProtocolMessageTypes.ClipPayloadEnd, Guid.NewGuid(), new ClipPayloadEndBody
        {
            TransferId = transferId,
            EventId = eventId,
            ContentHash = contentHash,
        }));

        // Receiver: every frame must pass the strict v2 reader, then reassemble and commit.
        var begin = Assert.IsType<ClipPayloadBeginBody>(ParsedBody(frames[0]));
        Assert.Equal(contentHash, begin.ContentHash);
        Assert.Equal(encoded.Length, begin.EncodedBytes);
        Assert.Equal(mime, begin.MimeType);

        using var stream = new MemoryStream();
        for (var index = 0; index < chunks.Count; index++)
        {
            var chunk = Assert.IsType<ClipPayloadChunkBody>(ParsedBody(frames[1 + index]));
            Assert.Equal(begin.TransferId, chunk.TransferId);
            Assert.Equal(index, chunk.ChunkIndex);
            Assert.True(ImageChunks.TryDecodeChunk(chunk.Data, (int)chunk.ChunkBytes, out var bytes));
            stream.Write(bytes);
        }

        var end = Assert.IsType<ClipPayloadEndBody>(ParsedBody(frames[^1]));
        Assert.Equal(begin.ContentHash, end.ContentHash);

        var reassembled = stream.ToArray();
        Assert.Equal(encoded, reassembled);
        Assert.Equal(contentHash, ImageCodec.HashBytes(reassembled));

        using var root = new TemporaryDirectory();
        var store = new MediaBlobStore(root.Path);
        var committed = store.CommitBytes(reassembled, contentHash);
        Assert.Equal(contentHash, committed.ContentHash);
        Assert.True(store.Exists(contentHash));
        Assert.Equal(encoded, store.ReadAllBytes(contentHash));
    }

    [Fact]
    public void WireFixturesBindTheSharedPng8x8SampleExactly()
    {
        var encoded = File.ReadAllBytes(Path.Combine(MediaRoot, "png-8x8.png"));
        var manifest = Manifest();
        var expectedHash = manifest.GetProperty("png_8x8_sha256").GetString();
        var chunk0Bytes = manifest.GetProperty("png_8x8_chunk0_bytes").GetInt32();
        var chunk1Bytes = manifest.GetProperty("png_8x8_chunk1_bytes").GetInt32();
        Assert.Equal(encoded.Length, chunk0Bytes + chunk1Bytes);
        Assert.Equal(expectedHash, ImageCodec.HashBytes(encoded));

        var begin = Assert.IsType<ClipPayloadBeginBody>(ParsedBody(ReadValidFixture("clip_payload_begin")));
        Assert.Equal(expectedHash, begin.ContentHash);
        Assert.Equal(encoded.Length, begin.EncodedBytes);
        Assert.Equal(2, begin.ChunkCount);
        Assert.Equal(MediaLimits.MimePng, begin.MimeType);

        var chunk0 = Assert.IsType<ClipPayloadChunkBody>(ParsedBody(ReadValidFixture("clip_payload_chunk")));
        Assert.Equal(begin.TransferId, chunk0.TransferId);
        Assert.Equal(0, chunk0.ChunkIndex);
        Assert.Equal(chunk0Bytes, chunk0.ChunkBytes);
        Assert.Equal(ProtocolValidation.EncodeBase64Url(encoded.AsSpan(0, chunk0Bytes)), chunk0.Data);

        // Reassemble the fixture-declared split: fixture chunk 0 + locally derived chunk 1.
        Assert.True(ImageChunks.TryDecodeChunk(chunk0.Data, chunk0Bytes, out var first));
        var reassembled = first.Concat(encoded.Skip(chunk0Bytes)).ToArray();
        Assert.Equal(encoded, reassembled);

        var end = Assert.IsType<ClipPayloadEndBody>(ParsedBody(ReadValidFixture("clip_payload_end")));
        Assert.Equal(begin.TransferId, end.TransferId);
        Assert.Equal(expectedHash, end.ContentHash);
    }

    [Fact]
    public void V1ReaderRejectsEveryV2ImageFrameSoTextOnlyPeersStayClean()
    {
        foreach (var name in new[] { "clip_payload_begin", "clip_payload_chunk", "clip_payload_end" })
        {
            var outcome = ProtocolReader.Parse(ReadValidFixture(name));
            var failure = Assert.IsType<ProtocolParseOutcome.Failure>(outcome);
            Assert.Equal(ProtocolErrorCodes.UnsupportedVersion, failure.ErrorCode);
        }
    }

    private static object ParsedBody(string frame)
    {
        var success = Assert.IsType<ProtocolParseOutcome.Success>(ProtocolReaderV2.Parse(frame));
        return success.Body;
    }

    private static string ReadValidFixture(string name) =>
        File.ReadAllText(Path.Combine(ValidRoot, name + ".json"));

    private static JsonElement Manifest()
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(MediaRoot, "manifest.json")));
        return document.RootElement.Clone();
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "clipsync-roundtrip", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            if (Directory.Exists(Path))
            {
                Directory.Delete(Path, recursive: true);
            }
        }
    }
}
