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

    /// <summary>
    /// Commit trusts the hash it accumulated while streaming (no second read of the file),
    /// so the result must still carry the file's real digest and still reject a peer whose
    /// announced hash does not match what actually arrived.
    /// </summary>
    [Fact]
    public void StreamedCommitReportsTheStreamedHashAndRejectsMismatch()
    {
        using var root = new TemporaryMediaRoot();
        var store = new MediaBlobStore(root.Path);
        var png = ImageCodec.EncodePngBgra(300, 7, new byte[300 * 7 * 4]);
        var expectedHash = ImageCodec.HashBytes(png);

        var pending = store.BeginWrite();
        foreach (var chunk in png.Chunk(1000))
        {
            MediaBlobStore.Append(pending, chunk);
        }

        var image = store.Commit(pending, expectedHash, "image/png");
        Assert.Equal(expectedHash, image.ContentHash);
        Assert.Equal(ImageCodec.HashFile(store.RequirePath(expectedHash)), image.ContentHash);
        Assert.Equal(png.Length, image.EncodedBytes);
        Assert.Equal(300, image.PixelWidth);
        Assert.Equal(7, image.PixelHeight);

        var mismatch = store.BeginWrite();
        MediaBlobStore.Append(mismatch, png);
        var error = Assert.Throws<InvalidDataException>(() => store.Commit(mismatch, new string('0', 64), "image/png"));
        Assert.Equal("MEDIA_HASH_MISMATCH", error.Message);

        var truncated = store.BeginWrite();
        MediaBlobStore.Append(truncated, "not an image at all, just twenty-eight bytes"u8);
        var unsupported = Assert.Throws<InvalidDataException>(() => store.Commit(truncated));
        Assert.Equal("UNSUPPORTED_MEDIA", unsupported.Message);
        Assert.Empty(Directory.EnumerateFiles(Path.Combine(root.Path, MediaBlobStore.TempDirectoryName)));
    }

    [Fact]
    public void HeaderInspectAgreesWithFullInspectWithoutHashing()
    {
        using var root = new TemporaryMediaRoot();
        var store = new MediaBlobStore(root.Path);
        var png = ImageCodec.EncodePngBgra(640, 360, new byte[640 * 360 * 4]);
        var committed = store.CommitBytes(png);
        var path = store.RequirePath(committed.ContentHash);

        Assert.Equal(ImageCodecError.Ok, ImageCodec.TryInspectFileHeader(path, out var header));
        Assert.Equal(ImageCodecError.Ok, ImageCodec.TryInspectFile(path, out var full));
        Assert.Equal(full!.MimeType, header!.MimeType);
        Assert.Equal(full.EncodedBytes, header.EncodedBytes);
        Assert.Equal(full.PixelWidth, header.PixelWidth);
        Assert.Equal(full.PixelHeight, header.PixelHeight);
        Assert.Equal(ImageCodecError.HashMismatch, ImageCodec.TryInspectFileHeader(path, out _, expectedBytes: png.Length + 1));
        Assert.Equal(ImageCodecError.DecodeFailed, ImageCodec.TryInspectFileHeader(path + ".missing", out _));
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
