using ClipSync.Core.Media;

namespace ClipSync.Tests.Media;

public sealed class MediaBlobStoreTests
{
    [Fact]
    public void CommitBytesStoresPngOnceAndIsIdempotent()
    {
        using var root = new TemporaryMediaRoot();
        var store = new MediaBlobStore(root.Path);
        var png = ImageCodec.EncodePngBgra(8, 8, new byte[8 * 8 * 4]);
        var first = store.CommitBytes(png);
        var second = store.CommitBytes(png);

        Assert.Equal(first.ContentHash, second.ContentHash);
        Assert.Equal("image/png", first.MimeType);
        Assert.Equal(8, first.PixelWidth);
        Assert.True(store.Exists(first.ContentHash));
        Assert.Equal(png, store.ReadAllBytes(first.ContentHash));
    }

    [Fact]
    public void RejectsGifMagicWithoutWritingABlob()
    {
        using var root = new TemporaryMediaRoot();
        var store = new MediaBlobStore(root.Path);
        var gif = "GIF89a"u8.ToArray().Concat(new byte[32]).ToArray();

        var error = Assert.Throws<InvalidDataException>(() => store.CommitBytes(gif));
        Assert.Equal("UNSUPPORTED_MEDIA", error.Message);
        Assert.Empty(Directory.EnumerateFiles(Path.Combine(root.Path, MediaBlobStore.BlobsDirectoryName)));
    }

    [Fact]
    public void RecoverTempsDeletesExpiredPartsOnly()
    {
        using var root = new TemporaryMediaRoot();
        var store = new MediaBlobStore(root.Path);
        var temps = Path.Combine(root.Path, MediaBlobStore.TempDirectoryName);
        var stale = Path.Combine(temps, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.part");
        var fresh = Path.Combine(temps, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.part");
        File.WriteAllBytes(stale, [1]);
        File.WriteAllBytes(fresh, [2]);
        File.SetLastWriteTimeUtc(stale, DateTime.UtcNow.AddHours(-25));
        File.SetLastWriteTimeUtc(fresh, DateTime.UtcNow);

        Assert.Equal(1, store.RecoverTemps(DateTimeOffset.UtcNow));
        Assert.False(File.Exists(stale));
        Assert.True(File.Exists(fresh));
    }

    private sealed class TemporaryMediaRoot : IDisposable
    {
        public TemporaryMediaRoot()
        {
            Path = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(),
                "clipsync-tests",
                Guid.NewGuid().ToString("N"),
                "media");
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            if (Directory.Exists(System.IO.Path.GetDirectoryName(Path)))
            {
                Directory.Delete(System.IO.Path.GetDirectoryName(Path)!, recursive: true);
            }
        }
    }
}
