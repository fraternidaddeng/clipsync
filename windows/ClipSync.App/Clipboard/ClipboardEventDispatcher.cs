using System.Windows.Threading;

namespace ClipSync.App.Clipboard;

internal interface IClipboardEventDispatcher
{
    bool TryPost(Action action);
}

internal sealed class WpfClipboardEventDispatcher(Dispatcher dispatcher) : IClipboardEventDispatcher
{
    public bool TryPost(Action action)
    {
        ArgumentNullException.ThrowIfNull(action);
        if (dispatcher.HasShutdownStarted || dispatcher.HasShutdownFinished)
        {
            return false;
        }

        try
        {
            // Normal, not Send: Send can nest inside BitmapImage/ListBox layout
            // and re-enter clipboard capture until the stack overflows (c00000fd).
            _ = dispatcher.BeginInvoke(DispatcherPriority.Normal, action);
            return true;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }
}

internal sealed class ImmediateClipboardEventDispatcher : IClipboardEventDispatcher
{
    internal static ImmediateClipboardEventDispatcher Instance { get; } = new();

    private ImmediateClipboardEventDispatcher()
    {
    }

    public bool TryPost(Action action)
    {
        action();
        return true;
    }
}
