using ClipSync.Core.Media;

namespace ClipSync.Tests.Media;

public sealed class ImageCodecEncodeTests
{
    /// <summary>
    /// Encoded bytes (and therefore content hashes, dedup, and the pixel-digest echo
    /// guard) must not drift when the encoder's buffering changes. The digests were
    /// produced by the pre-optimization encoder on the same deterministic inputs.
    /// </summary>
    [Theory]
    [InlineData(64, 48, 1, 12404, "788787d1baea13bec2ba407ee20d754064c6af4f95e75e0b42df4ff5fcd89c7c")]
    [InlineData(33, 17, 2, 2329, "ffce4bcce1bdad206465a1393fd3b9bc147235500199c16010b0f447686941ee")]
    [InlineData(1, 1, 3, 70, "671533b828f9905207cdc6a77c4f13c0a1e8019ec1d70beedccbd3801a4e1180")]
    [InlineData(300, 200, 4, 2175, "7131021e7f0811ed8255c338af9d9ff84576c278e8502b5f3c829744b4b369f1")]
    public void EncodedBytesAreIdenticalToTheReferenceEncoder(int width, int height, int seed, int expectedLength, string expectedSha256)
    {
        var bgra = new byte[width * height * 4];
        var state = (uint)seed;
        for (var index = 0; index < bgra.Length; index++)
        {
            state = (state * 1664525u) + 1013904223u;
            bgra[index] = seed == 4 ? (byte)((index / 4) % 251) : (byte)(state >> 24);
        }

        var png = ImageCodec.EncodePngBgra(width, height, bgra);

        Assert.Equal(expectedLength, png.Length);
        Assert.Equal(expectedSha256, ImageCodec.HashBytes(png));
        Assert.Equal(ImageCodecError.Ok, ImageCodec.TryInspect(png, out var inspected));
        Assert.Equal(width, inspected!.PixelWidth);
        Assert.Equal(height, inspected.PixelHeight);
    }

    /// <summary>
    /// The clipboard listener runs the DIB→PNG encode on a thread with the default 1 MiB
    /// stack. An incompressible 1024×512 frame yields a ~2 MiB IDAT chunk; the encoder
    /// must never size a stack buffer by payload length.
    /// </summary>
    [Fact]
    public void EncodesMultiMegabyteIdatOnADefaultStackThread()
    {
        var bgra = new byte[1024 * 512 * 4];
        var state = 12345u;
        for (var index = 0; index < bgra.Length; index++)
        {
            state = (state * 1664525u) + 1013904223u;
            bgra[index] = (byte)(state >> 24);
        }

        byte[]? png = null;
        Exception? failure = null;
        var thread = new Thread(
            () =>
            {
                try
                {
                    png = ImageCodec.EncodePngBgra(1024, 512, bgra);
                }
                catch (Exception exception)
                {
                    failure = exception;
                }
            },
            maxStackSize: 1024 * 1024);
        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(60)));
        Assert.Null(failure);
        Assert.NotNull(png);
        Assert.True(png.Length > 1024 * 1024, $"noise should not compress: {png.Length}");
        Assert.Equal(ImageCodecError.Ok, ImageCodec.TryInspect(png, out var inspected));
        Assert.Equal(1024, inspected!.PixelWidth);
        Assert.Equal(512, inspected.PixelHeight);
    }
}
