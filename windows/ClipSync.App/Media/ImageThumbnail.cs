using System.IO;
using System.Windows.Media.Imaging;
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
            return null;
        }
        catch (ArgumentException)
        {
            return null;
        }

        try
        {
            var source = DecodeBounded(blob);
            if (source is null)
            {
                return null;
            }

            var directory = System.IO.Path.GetDirectoryName(destination);
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            var tempPath = destination + ".part";
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(source));
            using (var output = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                encoder.Save(output);
            }

            File.Move(tempPath, destination, overwrite: true);
            return File.Exists(destination) ? destination : null;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException
            or NotSupportedException or InvalidOperationException or ArgumentException
            or FormatException or FileFormatException)
        {
            try
            {
                File.Delete(destination + ".part");
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }

            return null;
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
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException
            or NotSupportedException or InvalidOperationException or ArgumentException
            or FormatException or FileFormatException)
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
