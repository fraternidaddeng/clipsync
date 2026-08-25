using ClipSync.App.Localization;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace ClipSync.App.Diagnostics;

/// <summary>
/// Minimal, read-only viewer for the in-memory diagnostics ring buffer. It shows status codes and
/// timestamps only — never clipboard content — and is built entirely in code so it stays
/// self-contained and does not depend on the window theme dictionaries the app is still evolving.
/// Opened from the tray menu; a single instance is reused.
/// </summary>
internal sealed class DiagnosticsWindow : Window
{
    private static readonly Brush PanelBackground = Frozen(Color.FromRgb(0x12, 0x18, 0x20));
    private static readonly Brush LogBackground = Frozen(Color.FromRgb(0x0C, 0x11, 0x16));
    private static readonly Brush PrimaryText = Frozen(Color.FromRgb(0xE3, 0xE9, 0xF0));
    private static readonly Brush MutedText = Frozen(Color.FromRgb(0xB6, 0xC2, 0xD0));

    private readonly TextBox logView;

    public DiagnosticsWindow()
    {
        Title = Strings.Diag_Title;
        Width = 520;
        Height = 440;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = PanelBackground;
        // 阿拉伯语 RTL（P1#16）：整窗镜像；日志正文是机器文本，保持 LTR。
        FlowDirection = LocalizationManager.WindowFlowDirection;

        var root = new DockPanel { Margin = new Thickness(14) };

        var heading = new TextBlock
        {
            Text = Strings.Diag_Heading,
            Foreground = MutedText,
            FontSize = 12,
            Margin = new Thickness(0, 0, 0, 10),
            TextWrapping = TextWrapping.Wrap,
        };
        DockPanel.SetDock(heading, Dock.Top);
        root.Children.Add(heading);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Margin = new Thickness(0, 10, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Right,
        };
        var refreshButton = new Button
        {
            Content = Strings.Common_Refresh,
            Padding = new Thickness(14, 4, 14, 4),
            Margin = new Thickness(0, 0, 8, 0),
        };
        refreshButton.Click += (_, _) => Reload();
        var copyButton = new Button
        {
            Content = Strings.Diag_CopyAll,
            Padding = new Thickness(14, 4, 14, 4),
        };
        copyButton.Click += (_, _) => CopyAll();
        buttons.Children.Add(refreshButton);
        buttons.Children.Add(copyButton);
        DockPanel.SetDock(buttons, Dock.Bottom);
        root.Children.Add(buttons);

        logView = new TextBox
        {
            IsReadOnly = true,
            FlowDirection = FlowDirection.LeftToRight,
            TextWrapping = TextWrapping.NoWrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            FontFamily = new FontFamily("Consolas, Cascadia Mono"),
            FontSize = 12,
            Background = LogBackground,
            Foreground = PrimaryText,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(10),
        };
        root.Children.Add(logView);

        Content = root;
        Reload();
    }

    private void Reload()
    {
        var snapshot = LocalDiagnostics.Snapshot();
        if (snapshot.Count == 0)
        {
            logView.Text = Strings.Diag_Empty;
            return;
        }

        var builder = new StringBuilder();
        foreach (var entry in snapshot)
        {
            builder.Append(entry.TimestampUtc.ToLocalTime().ToString(
                "MM-dd HH:mm:ss",
                System.Globalization.CultureInfo.CurrentCulture));
            builder.Append("  ");
            builder.AppendLine(entry.Code);
        }

        logView.Text = builder.ToString();
    }

    private void CopyAll()
    {
        try
        {
            // Fully qualified: the sibling ClipSync.App.Clipboard namespace shadows the type.
            System.Windows.Clipboard.SetText(logView.Text);
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            // The clipboard is momentarily locked by another process; the viewer stays usable.
        }
    }

    private static SolidColorBrush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }
}
