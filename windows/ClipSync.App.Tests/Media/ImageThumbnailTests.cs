using System.IO;
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

    public void Dispose()
    {
        if (Directory.Exists(root))
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
