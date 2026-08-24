using System.Runtime.InteropServices;
using System.Windows.Threading;
using ClipSync.App.Clipboard;

namespace ClipSync.App.Tests.Clipboard;

public sealed class MessageOnlyClipboardWindowSmokeTests
{
    [Fact]
    public void RealMessageOnlyWindowRegistersAndReceivesClipboardUpdateMessage()
    {
        Exception? failure = null;
        var completed = new ManualResetEventSlim();
        var thread = new Thread(() =>
        {
            try
            {
                using var window = new MessageOnlyClipboardWindow();
                var notificationCount = 0;
                window.ClipboardUpdated += (_, _) => notificationCount++;

                window.Start();
                Assert.NotEqual(nint.Zero, window.Handle);
                Assert.Equal(nint.Zero, GetWindowLongPtr(window.Handle, -8));
                Assert.NotEqual(nint.Zero, GetWindowLongPtr(window.Handle, -20));
                _ = SendMessage(window.Handle, MessageOnlyClipboardWindow.ClipboardUpdateMessage, nint.Zero, nint.Zero);
                Assert.Equal(1, notificationCount);

                window.Stop();
                Assert.Equal(nint.Zero, window.Handle);
            }
            catch (Exception exception)
            {
                failure = exception;
            }
            finally
            {
                completed.Set();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();

        Assert.True(completed.Wait(TimeSpan.FromSeconds(10)), "The clipboard listener STA did not complete.");
        Assert.True(thread.Join(TimeSpan.FromSeconds(1)), "The clipboard listener STA did not exit.");
        Assert.Null(failure);
    }

    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("user32.dll")]
    private static extern nint SendMessage(nint window, int message, nint wordParameter, nint longParameter);

    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern nint GetWindowLongPtr(nint window, int index);
}
