using ClipSync.App;
using Hardcodet.Wpf.TaskbarNotification;
using System.Drawing;
using System.Windows;
using System.Windows.Controls;

namespace ClipSync.App.Tray;

internal static class TrayIconFactory
{
    public static TaskbarIcon Create(Window mainWindow, Action exit)
    {
        var menu = new ContextMenu();
        var openItem = new MenuItem { Header = Strings.TrayOpen };
        openItem.Click += (_, _) => Show(mainWindow);
        menu.Items.Add(openItem);
        menu.Items.Add(new Separator());
        var exitItem = new MenuItem { Header = Strings.TrayExit };
        exitItem.Click += (_, _) => exit();
        menu.Items.Add(exitItem);

        var icon = new TaskbarIcon
        {
            ToolTipText = Strings.TrayTooltip,
            Icon = SystemIcons.Application,
            ContextMenu = menu
        };
        icon.TrayLeftMouseDown += (_, _) => Show(mainWindow);
        return icon;
    }

    private static void Show(Window window)
    {
        window.Show();
        if (window.WindowState == WindowState.Minimized)
        {
            window.WindowState = WindowState.Normal;
        }

        window.Activate();
    }
}
