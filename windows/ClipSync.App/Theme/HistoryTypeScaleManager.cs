using ClipSync.App.Ui;
using System.Windows;

namespace ClipSync.App.Theme;

/// <summary>
/// Pushes the 历史字号/预览行数 settings into the application resource dictionary. The
/// history list, detail window and tray flyout consume these keys via DynamicResource,
/// so a change restyles every open window immediately. Entries written here sit directly
/// on Application.Resources and therefore survive the day/night token-dictionary swap
/// (<see cref="CharterThemeManager"/> replaces merged dictionaries only).
/// </summary>
internal static class HistoryTypeScaleManager
{
    public static void Apply(double scale, int previewLines)
    {
        if (Application.Current is not { } application)
        {
            return;
        }

        var resources = application.Resources;
        resources["CsHistoryBodyFontSize"] = HistoryDisplayOptions.BodyFontSize(scale);
        resources["CsHistoryBodyLineHeight"] = HistoryDisplayOptions.BodyLineHeight(scale);
        resources["CsHistoryPreviewMaxHeight"] = HistoryDisplayOptions.PreviewMaxHeight(scale, previewLines);
        resources["CsDetailBodyFontSize"] = HistoryDisplayOptions.DetailBodyFontSize(scale);
        resources["CsFlyoutBodyFontSize"] = HistoryDisplayOptions.FlyoutFontSize(scale);
        resources["CsFlyoutBodyLineHeight"] = HistoryDisplayOptions.FlyoutLineHeight(scale);
        resources["CsFlyoutBodyMaxHeight"] = HistoryDisplayOptions.FlyoutMaxHeight(scale);
    }
}
