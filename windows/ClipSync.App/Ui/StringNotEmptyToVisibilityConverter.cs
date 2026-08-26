using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace ClipSync.App.Ui;

/// <summary>
/// Non-empty string → Visible. 缺省即隐藏: transient facts (a QR being shown, a follow-up
/// hint) occupy no space at all until they actually have something to say.
/// </summary>
public sealed class StringNotEmptyToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is string text && text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
