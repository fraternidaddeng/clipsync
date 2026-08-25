using ClipSync.App.Ui;
using Microsoft.Win32;
using System.Windows;

namespace ClipSync.App.Theme;

/// <summary>
/// Keeps the application-level charter token dictionary (day or night) in step with the
/// effective theme. The whole dictionary is replaced at once — every key exists in both
/// CharterTokens.xaml and CharterTokensNight.xaml, and all windows consume the brushes
/// via DynamicResource, so a swap restyles every open window without a restart.
/// 外观（P1-6）: the default 跟随系统 mode follows SystemUsesLightTheme and reacts to
/// theme broadcasts; a manual 日间/夜间 override pins one token dictionary and ignores
/// the broadcasts until the mode returns to 跟随系统. The tray icons keep sampling the
/// taskbar theme regardless — they live in the taskbar, not in our windows.
/// </summary>
internal static class CharterThemeManager
{
    private const string PersonalizeKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private static readonly Uri DayTokensUri = new("pack://application:,,,/Resources/CharterTokens.xaml");
    private static readonly Uri NightTokensUri = new("pack://application:,,,/Resources/CharterTokensNight.xaml");

    private static bool listening;
    private static bool? appliedLight;
    private static string modeKey = AppearanceOptions.DefaultKey;

    /// <summary>Applies the current Windows theme and starts following theme changes.</summary>
    public static void Initialize()
    {
        Apply(IsWindowsLightTheme());
        SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;
        listening = true;
    }

    /// <summary>
    /// Applies the 外观 setting: 跟随系统 re-reads the Windows theme and resumes following
    /// its changes; 日间/夜间 pin their token dictionary immediately. Unreadable keys read
    /// as 跟随系统 (the ThemeOptions fallback), so a corrupt stored value never strands the
    /// UI on a stale palette.
    /// </summary>
    public static void SetMode(string? mode)
    {
        modeKey = AppearanceOptions.KeyForStored(mode);
        Apply(EffectiveLight());
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
        // A manual 日间/夜间 override pins the palette; system theme changes are ignored
        // until the mode returns to 跟随系统.
        if (modeKey != AppearanceOptions.SystemKey)
        {
            return;
        }

        // Light/dark flips broadcast as General; VisualStyle covers theme-pack changes.
        if (e.Category is not (UserPreferenceCategory.General or UserPreferenceCategory.VisualStyle))
        {
            return;
        }

        // SystemEvents raises on its own broadcast thread; resource swaps must happen on the
        // UI thread. The effective theme is re-read inside the dispatched work so a mode
        // change racing the broadcast can never override a just-pinned palette.
        Application.Current?.Dispatcher.BeginInvoke(() => Apply(EffectiveLight()));
    }

    /// <summary>The palette in effect right now: the pinned override, or the Windows theme.</summary>
    private static bool EffectiveLight() => AppearanceOptions.ForcedLight(modeKey) ?? IsWindowsLightTheme();

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
