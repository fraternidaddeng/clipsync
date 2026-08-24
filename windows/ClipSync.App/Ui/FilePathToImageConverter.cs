using System.Globalization;
using System.Windows.Data;
using ClipSync.App.Media;

namespace ClipSync.App.Ui;

/// <summary>
/// Binds a thumbnail file path to a decoded bitmap. Goes through
/// <see cref="BitmapFile"/> so no file URI (and its change watcher /
/// message pump) ever reaches the binding engine.
/// </summary>
public sealed class FilePathToImageConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is not string path)
        {
            return null;
        }

        return BitmapFile.TryLoad(path, decodePixelWidth: 128);
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
