using System.Globalization;
using System.Text;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;
using Xunit.Abstractions;

namespace ClipSync.Tests.Perf;

/// <summary>
/// Frame codec and image path measurements. Run with
/// <c>CLIPSYNC_PERF=1 dotnet test --filter FullyQualifiedName~ProtocolAndMediaPerfBenchmarks</c>.
/// </summary>
public sealed class ProtocolAndMediaPerfBenchmarks(ITestOutputHelper output)
{
    private const string TransferId = "6f1d2c3b-4a5e-4f60-8b9c-0d1e2f3a4b5c";
    private const string EventId = "7a2e3d4c-5b6f-4a71-9c0d-1e2f3a4b5c6d";

    [PerfFact]
    public void ChunkFrameRoundTrip()
    {
        var chunk = NoiseBytes(MediaLimits.MaxChunkBytes, seed: 11);
        var data = ProtocolValidation.EncodeBase64Url(chunk);
        var body = new ClipPayloadChunkBody
        {
            TransferId = TransferId,
            EventId = EventId,
            ChunkIndex = 3,
            ChunkCount = 8,
            ChunkBytes = chunk.Length,
            Data = data
        };
        var json = ProtocolWriter.Serialize(ProtocolLimits.ProtocolVersionV2, ProtocolMessageTypes.ClipPayloadChunk, Guid.NewGuid(), body);
        output.WriteLine(string.Format(CultureInfo.InvariantCulture, "chunk frame {0:N0} chars", json.Length));

        PerfProbe.Measure("EncodeBase64Url 256 KiB", 20, () => _ = ProtocolValidation.EncodeBase64Url(chunk), output);
        PerfProbe.Measure("TryDecodeBase64Url 256 KiB", 20, () => _ = ProtocolValidation.TryDecodeBase64Url(data, out _), output);
        PerfProbe.Measure("ImageChunks.TryDecodeChunk 256 KiB", 20, () => _ = ImageChunks.TryDecodeChunk(data, chunk.Length, out _), output);
        PerfProbe.Measure("ProtocolWriter.Serialize chunk frame", 20, () =>
            _ = ProtocolWriter.Serialize(ProtocolLimits.ProtocolVersionV2, ProtocolMessageTypes.ClipPayloadChunk, Guid.NewGuid(), body), output);
        PerfProbe.Measure("ProtocolReaderV2.Parse(string) chunk frame", 20, () =>
        {
            var outcome = ProtocolReaderV2.Parse(json);
            if (outcome is not ProtocolParseOutcome.Success)
            {
                throw new InvalidOperationException("frame rejected");
            }
        }, output);
        PerfProbe.Measure("Parse + TryDecodeChunk (receive path per chunk)", 20, () =>
        {
            var outcome = (ProtocolParseOutcome.Success)ProtocolReaderV2.Parse(json);
            var parsed = (ClipPayloadChunkBody)outcome.Body;
            if (!ImageChunks.TryDecodeChunk(parsed.Data, (int)parsed.ChunkBytes, out _))
            {
                throw new InvalidOperationException("chunk rejected");
            }
        }, output);

        var image = NoiseBytes(4 * 1024 * 1024, seed: 5);
        PerfProbe.Measure("ImageChunks.Split 4 MiB", 5, () => _ = ImageChunks.Split(image), output);
    }

    [PerfFact]
    public void TextPayloadFrame()
    {
        var content = new string('字', 100_000) + new string('a', 300_000);
        var body = new ClipPayloadBody
        {
            Clips =
            [
                new ClipPayloadItemDto
                {
                    EventId = EventId,
                    OriginDeviceId = TransferId,
                    OriginSeq = 42,
                    Kind = "text",
                    Content = content,
                    ContentHash = ProtocolValidation.ComputeContentHash(content),
                    Utf8Bytes = Encoding.UTF8.GetByteCount(content),
                    CreatedAtMs = 1_700_000_000_000
                }
            ]
        };
        var json = ProtocolWriter.Serialize(ProtocolLimits.ProtocolVersionV2, ProtocolMessageTypes.ClipPayload, Guid.NewGuid(), body);
        output.WriteLine(string.Format(CultureInfo.InvariantCulture, "text payload frame {0:N0} chars", json.Length));

        PerfProbe.Measure("ProtocolWriter.Serialize 600 KiB text payload", 10, () =>
            _ = ProtocolWriter.Serialize(ProtocolLimits.ProtocolVersionV2, ProtocolMessageTypes.ClipPayload, Guid.NewGuid(), body), output);
        PerfProbe.Measure("ProtocolReaderV2.Parse 600 KiB text payload", 10, () =>
        {
            if (ProtocolReaderV2.Parse(json) is not ProtocolParseOutcome.Success)
            {
                throw new InvalidOperationException("frame rejected");
            }
        }, output);
    }

    [PerfFact]
    public void ImagePath()
    {
        foreach (var side in new[] { 1024, 1600 })
        {
            var bgra = GradientBgra(side, side);
            output.WriteLine(string.Format(CultureInfo.InvariantCulture, "--- {0}x{0} BGRA {1:N0} bytes", side, bgra.Length));
            byte[] png = [];
            PerfProbe.Measure($"EncodePngBgra {side}x{side}", 3, () => png = ImageCodec.EncodePngBgra(side, side, bgra), output);
            output.WriteLine(string.Format(CultureInfo.InvariantCulture, "png size {0:N0} bytes", png.Length));

            var dib = DibCodec.EncodeDibBgra(side, side, bgra);
            PerfProbe.Measure($"DibCodec.TryDecodeToPng {side}x{side} (clipboard DIB capture)", 3, () =>
            {
                if (DibCodec.TryDecodeToPng(dib, out _, out _) != ImageCodecError.Ok)
                {
                    throw new InvalidOperationException("decode failed");
                }
            }, output);

            PerfProbe.Measure($"ImageCodec.HashBytes {png.Length:N0} B", 10, () => _ = ImageCodec.HashBytes(png), output);
            PerfProbe.Measure($"ImageCodec.TryInspect {png.Length:N0} B", 10, () => _ = ImageCodec.TryInspect(png, out _), output);

            var directory = Path.Combine(Path.GetTempPath(), "clipsync-perf", Guid.NewGuid().ToString("N"));
            try
            {
                var store = new MediaBlobStore(directory);
                var hash = ImageCodec.HashBytes(png);
                PerfProbe.Measure($"MediaBlobStore.CommitBytes {png.Length:N0} B (fresh)", 3, () =>
                {
                    store.DeleteBlob(hash);
                    store.CommitBytes(png, hash);
                }, output);
                PerfProbe.Measure($"MediaBlobStore Begin/Append/Commit {png.Length:N0} B (sync ingress)", 3, () =>
                {
                    store.DeleteBlob(hash);
                    var pending = store.BeginWrite();
                    foreach (var chunk in png.Chunk(MediaLimits.MaxChunkBytes))
                    {
                        MediaBlobStore.Append(pending, chunk);
                    }

                    store.Commit(pending, hash, MediaLimits.MimePng);
                }, output);
                var path = store.RequirePath(hash);
                PerfProbe.Measure($"ImageCodec.TryInspectFile {png.Length:N0} B", 10, () => _ = ImageCodec.TryInspectFile(path, out _), output);
            }
            finally
            {
                try
                {
                    Directory.Delete(directory, recursive: true);
                }
                catch (IOException)
                {
                }
            }
        }
    }

    private static byte[] NoiseBytes(int length, int seed)
    {
        var bytes = new byte[length];
        var state = (uint)seed;
        for (var index = 0; index < bytes.Length; index++)
        {
            state = (state * 1664525u) + 1013904223u;
            bytes[index] = (byte)(state >> 24);
        }

        return bytes;
    }

    /// <summary>Screenshot-like content: smooth gradients with text-like high-frequency stripes.</summary>
    private static byte[] GradientBgra(int width, int height)
    {
        var bgra = new byte[width * height * 4];
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var offset = ((y * width) + x) * 4;
                var stripe = (x / 3 + y / 7) % 5 == 0 ? (byte)30 : (byte)0;
                bgra[offset] = (byte)((x * 255 / width) ^ stripe);
                bgra[offset + 1] = (byte)((y * 255 / height) ^ stripe);
                bgra[offset + 2] = (byte)(((x + y) * 127 / (width + height)) + stripe);
                bgra[offset + 3] = 255;
            }
        }

        return bgra;
    }
}
