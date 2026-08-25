using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace ClipSync.App.Ui;

/// <summary>Which part of a device's tinted annotation box a brush paints.</summary>
public enum DeviceAccentRole
{
    /// <summary>The glyph / label colour (the accent itself).</summary>
    Text,

    /// <summary>The tinted box background (accent at 11% alpha).</summary>
    Background,

    /// <summary>The tinted box border (accent at 24% alpha).</summary>
    Line,
}

/// <summary>
/// Device neighbour colours (tokens §4) are assigned by pairing order — first device
/// hue 195, second 215, … — never hashed. Index 0 means "no accent": the local device
/// and remote clips whose origin is no longer paired fall back to the quiet grey family
/// (a fact, not an error).
/// </summary>
public static class DeviceAccent
{
    public const int None = 0;

    /// <summary>How many neighbour hues the charter defines before the wheel repeats.</summary>
    public const int PaletteSize = 5;

    /// <summary>Maps a zero-based pairing position (order of created_at) to accent 1..5, cycling.</summary>
    public static int ForPairingPosition(int position) => (position % PaletteSize) + 1;

    /// <summary>Resource key of the brush for the given accent and role; grey family for index 0.</summary>
    public static string BrushKey(int accentIndex, DeviceAccentRole role)
    {
        if (accentIndex is < 1 or > PaletteSize)
        {
            return role switch
            {
                DeviceAccentRole.Text => "CsText3Brush",
                DeviceAccentRole.Background => "CsSurface3Brush",
                _ => "CsLine2Brush",
            };
        }

        return role switch
        {
            DeviceAccentRole.Text => $"CsDev{accentIndex}Brush",
            DeviceAccentRole.Background => $"CsDev{accentIndex}BgBrush",
            _ => $"CsDev{accentIndex}LineBrush",
        };
    }
}

/// <summary>
/// Binds an accent index (see <see cref="DeviceAccent"/>) to the matching charter brush.
/// ConverterParameter selects the role: "Text", "Background" or "Line".
/// </summary>
public sealed class DeviceAccentBrushConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var index = value is int i ? i : DeviceAccent.None;
        var role = parameter is string name && Enum.TryParse<DeviceAccentRole>(name, out var parsed)
            ? parsed
            : DeviceAccentRole.Text;
        return Application.Current?.TryFindResource(DeviceAccent.BrushKey(index, role))
            ?? DependencyProperty.UnsetValue;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
