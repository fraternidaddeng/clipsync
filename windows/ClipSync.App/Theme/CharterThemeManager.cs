using ClipSync.App.Tray;
using ClipSync.App.Ui;
using Microsoft.Win32;
using System.Windows;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;

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
/// A live swap crossfades (tokens §9 dur-theme): each open window holds a frozen
/// snapshot of its outgoing palette on top and fades it out over the incoming one.
/// </summary>
internal static class CharterThemeManager
{
    private const string PersonalizeKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";

    /// <summary>日夜切换交叉淡化 (tokens §9 dur-theme 400–450ms; Windows speaks the faster end).</summary>
    private const double ThemeCrossfadeMs = 400;

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

            // dur-theme: snapshots of the outgoing palette are taken before the swap, and
            // the swap plus the fade start land in the same dispatcher frame — no hard cut
            // ever reaches the screen. The first Apply (startup, appliedLight == null) and
            // 减弱动效 (checked inside the capture) keep the instant swap.
            var snapshots = appliedLight is null
                ? []
                : CaptureOpenWindows(application);
            dictionaries[i] = new ResourceDictionary { Source = light ? DayTokensUri : NightTokensUri };
            appliedLight = light;
            foreach (var (layer, adorner) in snapshots)
            {
                FadeOutAndRemove(layer, adorner);
            }

            return;
        }
    }

    /// <summary>
    /// Freezes every open window's current pixels into an overlay adorner, so the incoming
    /// palette can appear underneath and shine through as the overlay fades. The tray flyout
    /// is deliberately excluded — no timed motion is its standing ruling (tokens §12.6).
    /// </summary>
    private static List<(AdornerLayer Layer, ThemeSnapshotAdorner Adorner)> CaptureOpenWindows(Application application)
    {
        var snapshots = new List<(AdornerLayer, ThemeSnapshotAdorner)>();
        if (!SystemParameters.ClientAreaAnimation)
        {
            return snapshots;
        }

        foreach (Window window in application.Windows)
        {
            if (window is TrayFlyoutWindow || !window.IsVisible || window.Content is not FrameworkElement root)
            {
                continue;
            }

            if (root.ActualWidth < 1 || root.ActualHeight < 1 ||
                AdornerLayer.GetAdornerLayer(root) is not { } layer ||
                RenderSnapshot(root) is not { } snapshot)
            {
                continue;
            }

            var adorner = new ThemeSnapshotAdorner(root, snapshot);
            layer.Add(adorner);
            snapshots.Add((layer, adorner));
        }

        return snapshots;
    }

    /// <summary>The window content as a frozen bitmap, at its own monitor's DPI.</summary>
    private static RenderTargetBitmap? RenderSnapshot(FrameworkElement root)
    {
        try
        {
            var dpi = VisualTreeHelper.GetDpi(root);
            var bitmap = new RenderTargetBitmap(
                Math.Max(1, (int)Math.Ceiling(root.ActualWidth * dpi.DpiScaleX)),
                Math.Max(1, (int)Math.Ceiling(root.ActualHeight * dpi.DpiScaleY)),
                dpi.PixelsPerInchX,
                dpi.PixelsPerInchY,
                PixelFormats.Pbgra32);
            // Rendered through a VisualBrush so the element's layout offset inside the
            // window never shears the copy (the classic RenderTargetBitmap trap).
            var visual = new DrawingVisual();
            using (var context = visual.RenderOpen())
            {
                context.DrawRectangle(
                    new VisualBrush(root),
                    null,
                    new Rect(0, 0, root.ActualWidth, root.ActualHeight));
            }

            bitmap.Render(visual);
            bitmap.Freeze();
            return bitmap;
        }
#pragma warning disable CA1031 // The crossfade is decoration: a failed snapshot must never block the palette swap.
        catch (Exception)
#pragma warning restore CA1031
        {
            return null;
        }
    }

    private static void FadeOutAndRemove(AdornerLayer layer, ThemeSnapshotAdorner adorner)
    {
        var fade = new DoubleAnimationUsingKeyFrames();
        fade.KeyFrames.Add(new SplineDoubleKeyFrame(
            0,
            KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ThemeCrossfadeMs)),
            new KeySpline(0.16, 1, 0.3, 1)));
        fade.Completed += (_, _) => layer.Remove(adorner);
        adorner.BeginAnimation(UIElement.OpacityProperty, fade);
    }

    /// <summary>
    /// A frozen picture of a window just before the token dictionaries swapped, held over
    /// the content and faded out — the outgoing half of the dur-theme crossfade. Never
    /// hit-testable: the window stays fully interactive underneath for the 400ms.
    /// </summary>
    private sealed class ThemeSnapshotAdorner : Adorner
    {
        private readonly BitmapSource snapshot;

        public ThemeSnapshotAdorner(UIElement adornedElement, BitmapSource snapshot)
            : base(adornedElement)
        {
            this.snapshot = snapshot;
            IsHitTestVisible = false;
        }

        protected override void OnRender(DrawingContext drawingContext) =>
            drawingContext.DrawImage(snapshot, new Rect(AdornedElement.RenderSize));
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
