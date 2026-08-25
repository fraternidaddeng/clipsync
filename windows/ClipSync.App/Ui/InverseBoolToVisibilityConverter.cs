using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace ClipSync.App.Ui;

/// <summary>
/// False → Visible. Shows the honest 无预览 placeholder exactly when a
/// thumbnail failed to decode, so an image row never renders as an
/// unexplained empty box.
/// </summary>
public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is false ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
