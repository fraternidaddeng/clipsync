using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ClipSync.App.Tests.Media;
using ClipSync.App.ViewModels;
using ClipSync.Core.Media;
using ClipSync.Core.Storage;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// The history list binds the frozen bitmap decoded in FromEntry — these tests
/// pin the whole 56 px preview contract: a committed PNG/JPEG blob yields an
/// opaque, frozen thumbnail image, and a missing blob yields the honest
/// placeholder state (HasThumbnail = false) instead of an unexplained blank.
/// </summary>
public sealed class HistoryItemThumbnailTests : IDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string root;
    private readonly MediaBlobStore store;

    public HistoryItemThumbnailTests()
    {
        root = Path.Combine(Path.GetTempPath(), "clipsync-item-thumb-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        store = new MediaBlobStore(root);
    }

    [Fact]
    public void FromEntryLoadsFrozenOpaqueThumbnailForPngBlob()
    {
        var png = ImageCodec.EncodePngBgra(
            320,
            200,
            ImageThumbnailTests.SolidBgra(320, 200, b: 30, g: 140, r: 80));
        var image = store.CommitBytes(png);

        var item = HistoryItemViewModel.FromEntry(ImageEntry(image), LocalDeviceId, media: store);

        Assert.True(item.IsImage);
        Assert.True(item.HasThumbnail);
        Assert.Equal(store.ThumbnailPath(image.ContentHash), item.ThumbnailPath);
        var bitmap = Assert.IsAssignableFrom<BitmapSource>(item.ThumbnailImage);
        Assert.True(bitmap.IsFrozen);
        ImageThumbnailTests.AssertCenterPixelIsSolid(bitmap, b: 30, g: 140, r: 80);
    }

    [Fact]
    public void FromEntryLoadsThumbnailForJpegBlob()
    {
        var image = store.CommitBytes(EncodeJpeg(96, 64, b: 20, g: 60, r: 180));
        Assert.Equal(MediaLimits.MimeJpeg, image.MimeType);

        var item = HistoryItemViewModel.FromEntry(ImageEntry(image), LocalDeviceId, media: store);

        Assert.True(item.HasThumbnail);
        var bitmap = Assert.IsAssignableFrom<BitmapSource>(item.ThumbnailImage);
        Assert.True(bitmap.IsFrozen);
        // JPEG is lossy: assert visibility (opaque, roughly the encoded colour)
        // rather than exact bytes.
        var (b, g, r, a) = ImageThumbnailTests.CenterPixel(bitmap);
        Assert.Equal(255, a);
        Assert.InRange(r, 150, 220);
        Assert.InRange(b, 0, 60);
    }

    [Fact]
    public void FromEntryHealsACorruptCachedThumbnail()
    {
        // Regression net for the "blob exists but the 56 px box stays grey" bug: a
        // stale cache file that no longer decodes must be regenerated from the blob
        // at bind time, never surfacing the 无预览 placeholder.
        var png = ImageCodec.EncodePngBgra(
            320,
            200,
            ImageThumbnailTests.SolidBgra(320, 200, b: 90, g: 45, r: 170));
        var image = store.CommitBytes(png);
        var garbage = new byte[128];
        Array.Fill(garbage, (byte)0xCD);
        File.WriteAllBytes(store.ThumbnailPath(image.ContentHash), garbage);

        var item = HistoryItemViewModel.FromEntry(ImageEntry(image), LocalDeviceId, media: store);

        Assert.True(item.HasThumbnail);
        Assert.False(item.ShowsNoPreview);
        Assert.Equal(store.ThumbnailPath(image.ContentHash), item.ThumbnailPath);
        var bitmap = Assert.IsAssignableFrom<BitmapSource>(item.ThumbnailImage);
        Assert.True(bitmap.IsFrozen);
        ImageThumbnailTests.AssertCenterPixelIsSolid(bitmap, b: 90, g: 45, r: 170);
    }

    [Fact]
    public void FromEntryFallsBackToPlaceholderStateWhenBlobIsMissing()
    {
        var entry = new ClipboardHistoryEntry(
            Guid.NewGuid(),
            LocalDeviceId,
            1,
            string.Empty,
            new string('a', 64),
            "snippingtool",
            DateTimeOffset.UtcNow,
            null,
            null,
            MediaLimits.KindImage,
            MediaLimits.MimePng,
            2048,
            320,
            200);

        var item = HistoryItemViewModel.FromEntry(entry, LocalDeviceId, media: store);

        Assert.True(item.IsImage);
        Assert.False(item.HasThumbnail);
        Assert.True(item.ShowsNoPreview);
        Assert.Null(item.ThumbnailImage);
        Assert.Null(item.ThumbnailPath);
        // The metadata line stays honest even without a preview.
        Assert.StartsWith("image/png 320×200", item.Preview, StringComparison.Ordinal);
    }

    private static ClipboardHistoryEntry ImageEntry(ValidatedImage image) => new(
        Guid.NewGuid(),
        LocalDeviceId,
        1,
        string.Empty,
        image.ContentHash,
        "snippingtool",
        DateTimeOffset.UtcNow,
        null,
        null,
        MediaLimits.KindImage,
        image.MimeType,
        image.EncodedBytes,
        image.PixelWidth,
        image.PixelHeight);

    private static byte[] EncodeJpeg(int width, int height, byte b, byte g, byte r)
    {
        var source = BitmapSource.Create(
            width,
            height,
            96,
            96,
            PixelFormats.Bgra32,
            null,
            ImageThumbnailTests.SolidBgra(width, height, b, g, r),
            width * 4);
        var encoder = new JpegBitmapEncoder { QualityLevel = 92 };
        encoder.Frames.Add(BitmapFrame.Create(source));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    public void Dispose()
    {
        if (Directory.Exists(root))
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
