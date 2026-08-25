using System.IO;
using System.Windows.Media.Imaging;

namespace ClipSync.App.Media;

/// <summary>
/// Loads a PNG/JPEG from disk without UriSource. Binding a file URI makes WPF
/// attach a change watcher and can pump window messages inside EndInit, which
/// re-enters clipboard capture and overflows the stack.
/// </summary>
internal static class BitmapFile
{
    public static BitmapImage? TryLoad(
        string path,
        int? decodePixelWidth = null,
        int? decodePixelHeight = null)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            return null;
        }

        try
        {
            // Copy first so EndInit never keeps a file URI or a live FileStream.
            // UriSource attaches a file watcher and can pump WM_CLIPBOARDUPDATE.
            var bytes = File.ReadAllBytes(path);
            using var stream = new MemoryStream(bytes, writable: false);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile | BitmapCreateOptions.IgnoreImageCache;
            image.StreamSource = stream;
            if (decodePixelWidth is > 0)
            {
                image.DecodePixelWidth = decodePixelWidth.Value;
            }

            if (decodePixelHeight is > 0)
            {
                image.DecodePixelHeight = decodePixelHeight.Value;
            }

            image.EndInit();
            image.Freeze();
            return image;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException
            or NotSupportedException or InvalidOperationException or ArgumentException
            or FormatException or FileFormatException
            or System.Runtime.InteropServices.COMException)
        {
            // WIC decode faults surface as COMException as well; a corrupt file must
            // degrade to the honest placeholder, never crash a history refresh.
            return null;
        }
    }
}
