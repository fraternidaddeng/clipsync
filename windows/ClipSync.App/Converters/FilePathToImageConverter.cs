using System.Globalization;
using System.Windows.Data;
using ClipSync.App.Media;

namespace ClipSync.App;

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
