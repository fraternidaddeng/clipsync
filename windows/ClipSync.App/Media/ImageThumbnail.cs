using System.IO;
using System.Windows.Media.Imaging;
using ClipSync.App.Diagnostics;
using ClipSync.Core.Media;

namespace ClipSync.App.Media;

/// <summary>
/// Writes a 512 px PNG thumbnail next to the content-addressed blob. History
/// UI never binds the original encoded file.
/// </summary>
internal static class ImageThumbnail
{
    public static string? Ensure(MediaBlobStore store, string contentHash)
    {
        ArgumentNullException.ThrowIfNull(store);
        ArgumentException.ThrowIfNullOrWhiteSpace(contentHash);

        var destination = store.ThumbnailPath(contentHash);
        if (File.Exists(destination) && new FileInfo(destination).Length > 0)
        {
            return destination;
        }

        string blob;
        try
        {
            blob = store.RequirePath(contentHash);
        }
        catch (FileNotFoundException)
        {
            LocalDiagnostics.Write("thumbnail_blob_missing");
            return null;
        }
        catch (ArgumentException)
        {
            LocalDiagnostics.Write("thumbnail_hash_invalid");
            return null;
        }

        // Unique temp name per attempt: a shared ".part" name makes overlapping
        // refreshes (capture + timer, or a re-entrant message pump) contend on the
        // exclusive FileStream — the loser throws IOException and the row silently
        // loses its thumbnail.
        var tempPath = destination + "." + Guid.NewGuid().ToString("N") + ".part";
        try
        {
            var source = DecodeBounded(blob);
            if (source is null)
            {
                LocalDiagnostics.Write("thumbnail_decode_failed");
                return null;
            }

            var directory = System.IO.Path.GetDirectoryName(destination);
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(source));
            using (var output = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                encoder.Save(output);
            }

            File.Move(tempPath, destination, overwrite: true);
            return File.Exists(destination) ? destination : null;
        }
        catch (Exception exception) when (IsRecoverableImagingError(exception))
        {
            TryDelete(tempPath);

            // A concurrent writer may have won the move; its thumbnail is identical
            // (content-addressed source), so the row still gets its preview.
            if (File.Exists(destination) && new FileInfo(destination).Length > 0)
            {
                return destination;
            }

            LocalDiagnostics.Write($"thumbnail_write_failed_{exception.GetType().Name}");
            return null;
        }
    }

    /// <summary>
    /// WIC surfaces decode/encode faults as COMException too; missing it turns a
    /// bad frame into an unhandled crash inside the history refresh.
    /// </summary>
    private static bool IsRecoverableImagingError(Exception exception) =>
        exception is IOException or UnauthorizedAccessException
            or NotSupportedException or InvalidOperationException or ArgumentException
            or FormatException or FileFormatException
            or System.Runtime.InteropServices.COMException;

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

    private static BitmapSource? DecodeBounded(string blobPath)
    {
        var inspect = ImageCodec.TryInspectFile(blobPath, out var image);
        if (inspect != ImageCodecError.Ok || image is null)
        {
            return null;
        }

        int? decodeWidth = null;
        int? decodeHeight = null;
        var longest = Math.Max(image.PixelWidth, image.PixelHeight);
        if (longest > MediaLimits.ThumbnailMaxSide)
        {
            if (image.PixelWidth >= image.PixelHeight)
            {
                decodeWidth = MediaLimits.ThumbnailMaxSide;
            }
            else
            {
                decodeHeight = MediaLimits.ThumbnailMaxSide;
            }
        }

        BitmapSource? loaded = BitmapFile.TryLoad(blobPath, decodeWidth, decodeHeight);
        return loaded ?? DecodeWithDecoder(blobPath);
    }

    private static BitmapSource? DecodeWithDecoder(string blobPath)
    {
        try
        {
            var bytes = File.ReadAllBytes(blobPath);
            using var stream = new MemoryStream(bytes, writable: false);
            var decoder = BitmapDecoder.Create(
                stream,
                BitmapCreateOptions.IgnoreColorProfile | BitmapCreateOptions.PreservePixelFormat,
                BitmapCacheOption.OnLoad);
            if (decoder.Frames.Count == 0)
            {
                return null;
            }

            var frame = decoder.Frames[0];
            frame.Freeze();
            return BoundToThumbnailSide(frame);
        }
        catch (Exception exception) when (IsRecoverableImagingError(exception))
        {
            return null;
        }
    }

    /// <summary>
    /// The fallback decoder has no DecodePixelWidth/Height, so an oversized frame
    /// would otherwise be re-encoded at full size and bound by every history row.
    /// Scale it down so the written "thumbnail" honours the 512 px contract too.
    /// </summary>
    private static BitmapSource BoundToThumbnailSide(BitmapSource source)
    {
        var longest = Math.Max(source.PixelWidth, source.PixelHeight);
        if (longest <= MediaLimits.ThumbnailMaxSide)
        {
            return source;
        }

        var scale = (double)MediaLimits.ThumbnailMaxSide / longest;
        var scaled = new TransformedBitmap(source, new System.Windows.Media.ScaleTransform(scale, scale));
        scaled.Freeze();
        return scaled;
    }
}
