namespace ClipSync.Core.Media;

/// <summary>Stage 9 static clipboard image limits. Shared by storage, protocol v2, and capture.</summary>
public static class MediaLimits
{
    public const int MaxEncodedBytes = 16 * 1024 * 1024;
    public const int MaxPixels = 32 * 1024 * 1024;
    public const int MaxSide = 8_192;
    public const int MaxChunkBytes = 256 * 1024;
    public const int MaxConcurrentDownloads = 2;
    public const int UnfinishedDownloadHours = 24;
    public const int ThumbnailMaxSide = 512;
    public const int MaxImagePayloadBatchEncodedBytes = MaxEncodedBytes;
    public const int MaxChunkCount = 64;

    public const string MimePng = "image/png";
    public const string MimeJpeg = "image/jpeg";
    public const string KindImage = "image";
    public const string KindText = "text";

    public const string BlobStateReady = "ready";
    public const string BlobStatePending = "pending";
    public const string BlobStateFailed = "failed";

    public const string ClipMediaReady = "ready";
    public const string ClipMediaPending = "pending";
    public const string ClipMediaMissing = "missing";

    public static bool IsSupportedMime(string? mime) =>
        string.Equals(mime, MimePng, StringComparison.Ordinal)
        || string.Equals(mime, MimeJpeg, StringComparison.Ordinal);

    public static bool FitsPixelBudget(int width, int height)
    {
        if (width is < 1 or > MaxSide || height is < 1 or > MaxSide)
        {
            return false;
        }

        return (long)width * height <= MaxPixels;
    }
}
