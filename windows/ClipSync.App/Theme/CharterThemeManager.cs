using Microsoft.Win32;
using System.Windows;

namespace ClipSync.App.Theme;

/// <summary>
/// Keeps the application-level charter token dictionary (day or night) in step with the
/// Windows theme. The whole dictionary is replaced at once — every key exists in both
/// CharterTokens.xaml and CharterTokensNight.xaml, and all windows consume the brushes
/// via DynamicResource, so a swap restyles every open window without a restart.
/// Follows SystemUsesLightTheme, the same value the tray icons sample, so the in-window
/// chrome and the tray never disagree about what theme the machine is in.
/// </summary>
internal static class CharterThemeManager
{
    private const string PersonalizeKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private static readonly Uri DayTokensUri = new("pack://application:,,,/Resources/CharterTokens.xaml");
    private static readonly Uri NightTokensUri = new("pack://application:,,,/Resources/CharterTokensNight.xaml");

    private static bool listening;
    private static bool? appliedLight;

    /// <summary>Applies the current Windows theme and starts following theme changes.</summary>
    public static void Initialize()
    {
        Apply(IsWindowsLightTheme());
        SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;
        listening = true;
    }

    /// <summary>Stops listening; SystemEvents handlers are process-global and must be detached.</summary>
    public static void Shutdown()
    {
        if (!listening)
        {
            return;
        }

        SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
        listening = false;
    }

    private static void OnUserPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
    {
        // Light/dark flips broadcast as General; VisualStyle covers theme-pack changes.
        if (e.Category is not (UserPreferenceCategory.General or UserPreferenceCategory.VisualStyle))
        {
            return;
        }

        // SystemEvents raises on its own broadcast thread; resource swaps must happen on the UI thread.
        Application.Current?.Dispatcher.BeginInvoke(() => Apply(IsWindowsLightTheme()));
    }

    private static void Apply(bool light)
    {
        if (appliedLight == light || Application.Current is not { } application)
        {
            return;
        }

        var dictionaries = application.Resources.MergedDictionaries;
        for (var i = 0; i < dictionaries.Count; i++)
        {
            if (dictionaries[i].Source is not { } source || !IsTokenDictionary(source))
            {
                continue;
            }

            dictionaries[i] = new ResourceDictionary { Source = light ? DayTokensUri : NightTokensUri };
            appliedLight = light;
            return;
        }
    }

    private static bool IsTokenDictionary(Uri source)
    {
        var path = source.OriginalString;
        return path.EndsWith("CharterTokens.xaml", StringComparison.OrdinalIgnoreCase)
            || path.EndsWith("CharterTokensNight.xaml", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Same convention as the tray: missing value means dark.</summary>
    private static bool IsWindowsLightTheme()
    {
        using var key = Registry.CurrentUser.OpenSubKey(PersonalizeKeyPath);
        return key?.GetValue("SystemUsesLightTheme") is int value && value != 0;
    }
}
