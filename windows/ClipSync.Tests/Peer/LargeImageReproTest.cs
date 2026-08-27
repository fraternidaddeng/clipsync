using ClipSync.Core.Media;
using ClipSync.Core.Storage;

namespace ClipSync.Tests.Peer;

/// <summary>
/// Real-size multi-chunk image transfer in both directions. Every other integration test
/// moves tiny single-chunk fixtures, so the begin → chunk-stream → end reassembly path —
/// the size class actual screenshots occupy — had no coverage until the 图片传输 regression
/// hunt added this pair. The noise payload is incompressible, so the encoded PNG is
/// guaranteed to span several MaxChunkBytes frames.
/// </summary>
public sealed class LargeImageReproTest
{
    private static byte[] NoisePng(int width, int height, int seed)
    {
        var bgra = new byte[width * height * 4];
        var state = (uint)seed;
        for (var index = 0; index < bgra.Length; index++)
        {
            state = (state * 1664525u) + 1013904223u;
            bgra[index] = (byte)(state >> 24);
        }

        return ImageCodec.EncodePngBgra(width, height, bgra);
    }

    [Fact]
    public async Task LargeImageListenerToDialer()
    {
        await using var pair = await PeerPair.CreateAsync();
        var png = NoisePng(512, 512, seed: 1);
        Assert.True(png.Length > MediaLimits.MaxChunkBytes, $"fixture too small: {png.Length}");
        var hash = ImageCodec.HashBytes(png);
        await PeerPair.CaptureImageAsync(pair.WindowsStore, png, hash, "image/png", width: 512, height: 512);

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
        {
            var items = await pair.AndroidStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
            return items.Any(item => item.IsImage && item.ContentHash == hash);
        });
        Assert.True(pair.AndroidStore.Media.Exists(hash));
        Assert.Equal(png, pair.AndroidStore.Media.ReadAllBytes(hash));
        await session.CloseAsync();
    }

    [Fact]
    public async Task LargeImageDialerToListener()
    {
        await using var pair = await PeerPair.CreateAsync();
        var png = NoisePng(512, 512, seed: 2);
        Assert.True(png.Length > MediaLimits.MaxChunkBytes, $"fixture too small: {png.Length}");
        var hash = ImageCodec.HashBytes(png);
        await PeerPair.CaptureImageAsync(pair.AndroidStore, png, hash, "image/png", width: 512, height: 512);

        var session = await pair.DialAsync();
        await pair.WaitUntilAsync(async () =>
        {
            var items = await pair.WindowsStore.SearchAsync(new ClipboardHistoryQuery(Limit: 50));
            return items.Any(item => item.IsImage && item.ContentHash == hash);
        });
        Assert.True(pair.WindowsStore.Media.Exists(hash));
        Assert.Equal(png, pair.WindowsStore.Media.ReadAllBytes(hash));
        await session.CloseAsync();
    }
}
