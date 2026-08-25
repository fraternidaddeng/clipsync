using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ClipSync.App.Media;
using ClipSync.Core.Media;

namespace ClipSync.App.Tests.Media;

public sealed class ImageThumbnailTests : IDisposable
{
    private readonly string root;

    public ImageThumbnailTests()
    {
        root = Path.Combine(Path.GetTempPath(), "clipsync-thumb-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
    }

    internal static byte[] SolidBgra(int width, int height, byte b, byte g, byte r, byte a = 255)
    {
        var pixels = new byte[width * height * 4];
        for (var i = 0; i < pixels.Length; i += 4)
        {
            pixels[i] = b;
            pixels[i + 1] = g;
            pixels[i + 2] = r;
            pixels[i + 3] = a;
        }

        return pixels;
    }

    internal static (byte B, byte G, byte R, byte A) CenterPixel(BitmapSource source)
    {
        var converted = new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);
        var pixel = new byte[4];
        converted.CopyPixels(
            new Int32Rect(source.PixelWidth / 2, source.PixelHeight / 2, 1, 1),
            pixel,
            4,
            0);
        return (pixel[0], pixel[1], pixel[2], pixel[3]);
    }

    [Fact]
    public void EnsureWritesPngThumbnailAndIsIdempotent()
    {
        var store = new MediaBlobStore(root);
        var png = ImageCodec.EncodePngBgra(8, 8, new byte[8 * 8 * 4]);
        var image = store.CommitBytes(png);

        var first = ImageThumbnail.Ensure(store, image.ContentHash);
        Assert.NotNull(first);
        Assert.True(File.Exists(first));
        Assert.Equal(store.ThumbnailPath(image.ContentHash), first);
        Assert.True(new FileInfo(first).Length > 0);
        var length = new FileInfo(first).Length;
        var stamp = File.GetLastWriteTimeUtc(first);

        var second = ImageThumbnail.Ensure(store, image.ContentHash);
        Assert.Equal(first, second);
        Assert.Equal(length, new FileInfo(second!).Length);
        Assert.Equal(stamp, File.GetLastWriteTimeUtc(second!));
    }

    [Fact]
    public void EnsureBoundsOversizedSourcesToTheThumbnailSide()
    {
        var store = new MediaBlobStore(root);
        var png = ImageCodec.EncodePngBgra(700, 300, new byte[700 * 300 * 4]);
        var image = store.CommitBytes(png);

        var path = ImageThumbnail.Ensure(store, image.ContentHash);

        Assert.NotNull(path);
        var inspect = ImageCodec.TryInspectFile(path!, out var thumb);
        Assert.Equal(ImageCodecError.Ok, inspect);
        Assert.NotNull(thumb);
        Assert.InRange(thumb!.PixelWidth, 1, MediaLimits.ThumbnailMaxSide);
        Assert.InRange(thumb.PixelHeight, 1, MediaLimits.ThumbnailMaxSide);
        // Aspect survives the bound: 700x300 shrinks by width, height follows.
        Assert.Equal(MediaLimits.ThumbnailMaxSide, thumb.PixelWidth);
    }

    [Fact]
    public void EnsureReturnsNullWhenBlobIsMissing()
    {
        var store = new MediaBlobStore(root);
        var missing = new string('a', 64);
        Assert.Null(ImageThumbnail.Ensure(store, missing));
    }

    [Fact]
    public void EnsureWritesValidPngThumbnailBytes()
    {
        var store = new MediaBlobStore(root);
        var png = ImageCodec.EncodePngBgra(8, 8, new byte[8 * 8 * 4]);
        var image = store.CommitBytes(png);
        var path = ImageThumbnail.Ensure(store, image.ContentHash);
        Assert.NotNull(path);
        var bytes = File.ReadAllBytes(path!);
        Assert.True(bytes.Length > 24);
        Assert.Equal(0x89, bytes[0]);
        Assert.Equal((byte)'P', bytes[1]);
        Assert.Equal((byte)'N', bytes[2]);
        Assert.Equal((byte)'G', bytes[3]);
        var inspect = ImageCodec.TryInspectFile(path!, out var thumb);
        Assert.Equal(ImageCodecError.Ok, inspect);
        Assert.NotNull(thumb);
        Assert.Equal("image/png", thumb!.MimeType);
        Assert.InRange(thumb.PixelWidth, 1, 512);
        Assert.InRange(thumb.PixelHeight, 1, 512);
    }

    [Fact]
    public void EnsureThumbnailKeepsOpaquePixels()
    {
        // Regression net for the blank-preview bug: a thumbnail whose bytes are a
        // valid PNG but whose pixels render invisible must fail this test.
        var store = new MediaBlobStore(root);
        var png = ImageCodec.EncodePngBgra(700, 300, SolidBgra(700, 300, b: 40, g: 90, r: 200));
        var image = store.CommitBytes(png);

        var path = ImageThumbnail.Ensure(store, image.ContentHash);

        Assert.NotNull(path);
        var thumbnail = BitmapFile.TryLoad(path!);
        Assert.NotNull(thumbnail);
        var (b, g, r, a) = CenterPixel(thumbnail!);
        Assert.Equal(255, a);
        Assert.Equal(40, b);
        Assert.Equal(90, g);
        Assert.Equal(200, r);
    }

    [Fact]
    public void EnsureConcurrentCallsBothReturnTheThumbnail()
    {
        // Overlapping refreshes used to contend on one shared ".part" temp file:
        // the loser hit an exclusive-lock IOException and its row silently lost
        // the preview. Unique temp names make both callers succeed.
        var store = new MediaBlobStore(root);
        for (var round = 0; round < 4; round++)
        {
            var png = ImageCodec.EncodePngBgra(640, 480, SolidBgra(640, 480, b: (byte)(10 + round), g: 120, r: 60));
            var image = store.CommitBytes(png);

            var results = new string?[2];
            Parallel.Invoke(
                () => results[0] = ImageThumbnail.Ensure(store, image.ContentHash),
                () => results[1] = ImageThumbnail.Ensure(store, image.ContentHash));

            Assert.NotNull(results[0]);
            Assert.NotNull(results[1]);
            Assert.Equal(store.ThumbnailPath(image.ContentHash), results[0]);
            Assert.Equal(results[0], results[1]);
            Assert.True(new FileInfo(results[0]!).Length > 0);
        }
    }

    [Fact]
    public void EnsureLeavesNoTempFilesBehind()
    {
        var store = new MediaBlobStore(root);
        var png = ImageCodec.EncodePngBgra(64, 64, SolidBgra(64, 64, b: 1, g: 2, r: 3));
        var image = store.CommitBytes(png);

        Assert.NotNull(ImageThumbnail.Ensure(store, image.ContentHash));

        var thumbsDirectory = Path.Combine(root, MediaBlobStore.ThumbnailsDirectoryName);
        Assert.Empty(Directory.EnumerateFiles(thumbsDirectory, "*.part"));
    }

    public void Dispose()
    {
        if (Directory.Exists(root))
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
