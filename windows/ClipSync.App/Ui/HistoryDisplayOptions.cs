using System.Globalization;

namespace ClipSync.App.Ui;

/// <summary>
/// 显示 · 历史字号与预览行数（settings-roadmap P0-1 / P1-7）。只缩放「别人的内容」——
/// 历史预览正文、详情正文、托盘浮窗正文；组头、注记盒、元信息与按钮属于纲领类型阶，
/// 保持定死。存储键 <c>ui_history_font_scale</c>（0.9 / 1.0 / 1.15）与
/// <c>ui_preview_lines</c>（2 / 4 / 6）；无法解读的存值一律回落默认，不报错。
/// </summary>
public static class HistoryDisplayOptions
{
    public const string SmallScaleKey = "small";
    public const string StandardScaleKey = "standard";
    public const string LargeScaleKey = "large";

    public const double SmallScale = 0.9;
    public const double StandardScale = 1.0;
    public const double LargeScale = 1.15;

    public const string DefaultLinesKey = "4";

    /// <summary>Base body size of a history preview line (the FontSize the XAML used to hard-code).</summary>
    public const double BaseBodyFontSize = 13;

    /// <summary>Explicit line height so the preview cap is an exact number of lines at every scale.</summary>
    public const double BaseBodyLineHeight = 18;

    /// <summary>The tray flyout body is one step smaller and always capped at two lines (tokens §12.6).</summary>
    public const double BaseFlyoutFontSize = 12;

    private const int FlyoutPreviewLines = 2;

    public static double ScaleFor(string? scaleKey) => scaleKey switch
    {
        SmallScaleKey => SmallScale,
        LargeScaleKey => LargeScale,
        _ => StandardScale,
    };

    /// <summary>Maps a stored factor ("0.9" / "1.0" / "1.15") back to its key; anything else reads as 标准.</summary>
    public static string ScaleKeyForStored(string? stored)
    {
        if (!double.TryParse(stored, NumberStyles.Float, CultureInfo.InvariantCulture, out var value))
        {
            return StandardScaleKey;
        }

        return value switch
        {
            SmallScale => SmallScaleKey,
            LargeScale => LargeScaleKey,
            _ => StandardScaleKey,
        };
    }

    /// <summary>The wire form of a scale key — the factor itself, per the roadmap key contract.</summary>
    public static string StoredScaleFor(string? scaleKey) =>
        ScaleFor(scaleKey).ToString(CultureInfo.InvariantCulture);

    public static int LinesFor(string? linesKey) => linesKey switch
    {
        "2" => 2,
        "6" => 6,
        _ => 4,
    };

    /// <summary>Maps a stored line count back to its key; anything outside 2/4/6 reads as 4.</summary>
    public static string LinesKeyForStored(string? stored) => stored switch
    {
        "2" or "6" => stored,
        _ => DefaultLinesKey,
    };

    public static string StoredLinesFor(string? linesKey) =>
        LinesFor(linesKey).ToString(CultureInfo.InvariantCulture);

    public static double BodyFontSize(double scale) => Math.Round(BaseBodyFontSize * scale, 2);

    public static double BodyLineHeight(double scale) => Math.Round(BaseBodyLineHeight * scale, 2);

    /// <summary>History-list preview cap: an exact number of body lines at the current scale.</summary>
    public static double PreviewMaxHeight(double scale, int previewLines) =>
        BodyLineHeight(scale) * previewLines;

    public static double FlyoutFontSize(double scale) => Math.Round(BaseFlyoutFontSize * scale, 2);

    public static double FlyoutLineHeight(double scale) => BodyLineHeight(scale);

    /// <summary>The flyout stays a two-line glance surface regardless of the preview-lines setting.</summary>
    public static double FlyoutMaxHeight(double scale) => FlyoutLineHeight(scale) * FlyoutPreviewLines;

    /// <summary>Detail-window body text (scrollable, no line cap — only the size scales).</summary>
    public static double DetailBodyFontSize(double scale) => Math.Round(BaseBodyFontSize * scale, 2);
}
