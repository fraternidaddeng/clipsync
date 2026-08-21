namespace ClipSync.Core.Media;

public sealed class PendingMediaWrite : IDisposable
{
    internal PendingMediaWrite(string tempPath, FileStream stream)
    {
        TempPath = tempPath;
        Stream = stream;
        Hasher = System.Security.Cryptography.IncrementalHash.CreateHash(
            System.Security.Cryptography.HashAlgorithmName.SHA256);
    }

    internal string TempPath { get; }

    internal FileStream Stream { get; }

    internal System.Security.Cryptography.IncrementalHash Hasher { get; }

    public long BytesWritten { get; internal set; }

    public void Dispose()
    {
        Stream.Dispose();
        Hasher.Dispose();
    }
}

/// <summary>
/// Content-addressed PNG/JPEG blob store. Callers never concatenate blob paths;
/// every file name is a validated SHA-256 hex digest or a UUID temp name.
/// </summary>
public sealed class MediaBlobStore
{
    public const string BlobsDirectoryName = "blobs";
    public const string TempDirectoryName = "tmp";
    public const string ThumbnailsDirectoryName = "thumbs";

    private readonly string root;
    private readonly string blobs;
    private readonly string temps;
    private readonly string thumbs;

    public MediaBlobStore(string rootDirectory)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        root = Path.GetFullPath(rootDirectory);
        blobs = Path.Combine(root, BlobsDirectoryName);
        temps = Path.Combine(root, TempDirectoryName);
        thumbs = Path.Combine(root, ThumbnailsDirectoryName);
        Directory.CreateDirectory(blobs);
        Directory.CreateDirectory(temps);
        Directory.CreateDirectory(thumbs);
    }

    public string RootDirectory => root;

    public static string DefaultRootForDatabase(string databasePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        var directory = Path.GetDirectoryName(Path.GetFullPath(databasePath));
        return Path.Combine(string.IsNullOrEmpty(directory) ? "." : directory, "media");
    }

    public PendingMediaWrite BeginWrite()
    {
        var tempPath = Path.Combine(temps, Guid.NewGuid().ToString("N") + ".part");
        var stream = new FileStream(
            tempPath,
            FileMode.CreateNew,
            FileAccess.Write,
            FileShare.None,
            bufferSize: 64 * 1024,
            FileOptions.SequentialScan);
        return new PendingMediaWrite(tempPath, stream);
    }

    public static void Append(PendingMediaWrite pending, ReadOnlySpan<byte> chunk)
    {
        ArgumentNullException.ThrowIfNull(pending);
        if (chunk.Length == 0)
        {
            throw new InvalidDataException("Image chunks cannot be empty.");
        }

        var next = pending.BytesWritten + chunk.Length;
        if (next > MediaLimits.MaxEncodedBytes)
        {
            throw new InvalidDataException("Encoded image exceeds 16 MiB.");
        }

        pending.Stream.Write(chunk);
        pending.Hasher.AppendData(chunk);
        pending.BytesWritten = next;
    }

    public ValidatedImage Commit(PendingMediaWrite pending, string? expectedHash = null, string? expectedMime = null)
    {
        ArgumentNullException.ThrowIfNull(pending);
        pending.Stream.Flush();
        var hash = Convert.ToHexString(pending.Hasher.GetHashAndReset()).ToLowerInvariant();
        pending.Dispose();

        try
        {
            if (expectedHash is not null && !string.Equals(expectedHash, hash, StringComparison.Ordinal))
            {
                throw new InvalidDataException("MEDIA_HASH_MISMATCH");
            }

            var inspect = ImageCodec.TryInspectFile(pending.TempPath, out var image, hash, pending.BytesWritten);
            if (inspect != ImageCodecError.Ok || image is null)
            {
                throw new InvalidDataException(inspect switch
                {
                    ImageCodecError.TooLarge => "MEDIA_TOO_LARGE",
                    ImageCodecError.HashMismatch => "MEDIA_HASH_MISMATCH",
                    ImageCodecError.UnsupportedMedia => "UNSUPPORTED_MEDIA",
                    _ => "MEDIA_DECODE_FAILED"
                });
            }

            if (expectedMime is not null && !string.Equals(expectedMime, image.MimeType, StringComparison.Ordinal))
            {
                throw new InvalidDataException("UNSUPPORTED_MEDIA");
            }

            var destination = BlobPath(image.ContentHash);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            if (File.Exists(destination))
            {
                File.Delete(pending.TempPath);
                return image;
            }

            File.Move(pending.TempPath, destination);
            return image;
        }
        catch
        {
            TryDelete(pending.TempPath);
            throw;
        }
    }

    public ValidatedImage CommitBytes(ReadOnlySpan<byte> encoded, string? expectedHash = null)
    {
        var inspect = ImageCodec.TryInspect(encoded, out var image, expectedHash);
        if (inspect != ImageCodecError.Ok || image is null)
        {
            throw new InvalidDataException(inspect switch
            {
                ImageCodecError.TooLarge => "MEDIA_TOO_LARGE",
                ImageCodecError.HashMismatch => "MEDIA_HASH_MISMATCH",
                ImageCodecError.UnsupportedMedia => "UNSUPPORTED_MEDIA",
                _ => "MEDIA_DECODE_FAILED"
            });
        }

        var destination = BlobPath(image.ContentHash);
        if (File.Exists(destination))
        {
            return image;
        }

        var pending = BeginWrite();
        try
        {
            Append(pending, encoded);
            return Commit(pending, image.ContentHash, image.MimeType);
        }
        catch
        {
            pending.Dispose();
            TryDelete(pending.TempPath);
            throw;
        }
    }

    public bool Exists(string contentHash) => File.Exists(BlobPath(contentHash));

    public string RequirePath(string contentHash)
    {
        var path = BlobPath(contentHash);
        if (!File.Exists(path))
        {
            throw new FileNotFoundException("Media blob is missing.");
        }

        return path;
    }

    public Stream OpenRead(string contentHash) =>
        new FileStream(RequirePath(contentHash), FileMode.Open, FileAccess.Read, FileShare.Read);

    public byte[] ReadAllBytes(string contentHash) => File.ReadAllBytes(RequirePath(contentHash));

    public string ThumbnailPath(string contentHash) =>
        Path.Combine(thumbs, NormalizeHash(contentHash) + ".png");

    public void DeleteBlob(string contentHash)
    {
        TryDelete(BlobPath(contentHash));
        TryDelete(ThumbnailPath(contentHash));
    }

    /// <summary>Deletes temp files older than 24 hours. Caps the number of deletions per call.</summary>
    public int RecoverTemps(DateTimeOffset now, int maximumDeletes = 256)
    {
        var cutoff = now.AddHours(-MediaLimits.UnfinishedDownloadHours);
        var removed = 0;
        if (!Directory.Exists(temps))
        {
            return 0;
        }

        foreach (var file in Directory.EnumerateFiles(temps, "*.part"))
        {
            if (removed >= maximumDeletes)
            {
                break;
            }

            var info = new FileInfo(file);
            if (info.LastWriteTimeUtc <= cutoff.UtcDateTime)
            {
                TryDelete(file);
                removed++;
            }
        }

        return removed;
    }

    public int DeleteUnreferenced(IReadOnlyCollection<string> liveHashes, int maximumDeletes = 256)
    {
        ArgumentNullException.ThrowIfNull(liveHashes);
        var live = new HashSet<string>(liveHashes, StringComparer.Ordinal);
        var removed = 0;
        if (!Directory.Exists(blobs))
        {
            return 0;
        }

        foreach (var file in Directory.EnumerateFiles(blobs, "*", SearchOption.AllDirectories))
        {
            if (removed >= maximumDeletes)
            {
                break;
            }

            var name = Path.GetFileName(file);
            if (name.Length != 64 || live.Contains(name))
            {
                continue;
            }

            TryDelete(file);
            TryDelete(Path.Combine(thumbs, name + ".png"));
            removed++;
        }

        return removed;
    }

    internal string BlobPath(string contentHash)
    {
        var hash = NormalizeHash(contentHash);
        return Path.Combine(blobs, hash);
    }

    private static string NormalizeHash(string contentHash)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(contentHash);
        if (contentHash.Length != 64)
        {
            throw new ArgumentException("content_hash must be 64 lowercase hex characters.", nameof(contentHash));
        }

        foreach (var character in contentHash)
        {
            if (character is not ((>= '0' and <= '9') or (>= 'a' and <= 'f')))
            {
                throw new ArgumentException("content_hash must be 64 lowercase hex characters.", nameof(contentHash));
            }
        }

        return contentHash;
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
