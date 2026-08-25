using System.Globalization;
using System.Windows.Data;

namespace ClipSync.App.Ui;

/// <summary>
/// Binds a string-keyed setting to a radio-chip group: the chip whose ConverterParameter
/// equals the property value reads as checked; checking a chip writes its parameter back.
/// Unchecking (the group toggling a sibling off) writes nothing, so the property only ever
/// moves forward to the newly picked key.
/// </summary>
public sealed class StringMatchToBoolConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        string.Equals(
            System.Convert.ToString(value, CultureInfo.InvariantCulture),
            System.Convert.ToString(parameter, CultureInfo.InvariantCulture),
            StringComparison.Ordinal);

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is true && parameter is not null
            ? System.Convert.ToString(parameter, CultureInfo.InvariantCulture)!
            : Binding.DoNothing;
}
