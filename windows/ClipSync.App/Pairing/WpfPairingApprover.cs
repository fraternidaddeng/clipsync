using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Threading;
using ClipSync.App.Diagnostics;
using ClipSync.Peer.Pairing;

namespace ClipSync.App.Pairing;

/// <summary>
/// Bridges the confirm endpoint (running on a Kestrel worker thread) to a WPF approval
/// window. Cancellation from the approval timeout closes the window and surfaces as
/// PAIRING_TIMEOUT rather than a rejection.
/// </summary>
/// <remarks>
/// The window alone is easy to miss: Activate() from a non-foreground process is refused by
/// the Windows foreground lock, so the taskbar button flashes until the user reaches the
/// window, and the two optional callbacks let the App add a tray balloon when the request
/// shows and when it lapses. Both callbacks run on the UI thread and receive at most the
/// candidate's display name (cut to <see cref="NoticeNameMaxLength"/> characters).
/// </remarks>
public sealed class WpfPairingApprover(
    Dispatcher dispatcher,
    Action<string>? onRequestShown = null,
    Action? onRequestTimedOut = null) : IPairingApprover
{
    /// <summary>Balloon titles have little room; the approval window still shows the full name.</summary>
    internal const int NoticeNameMaxLength = 40;

    public Task<bool> ApproveAsync(PairingCandidate candidate, CancellationToken cancellationToken)
    {
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = dispatcher.InvokeAsync(() =>
        {
            var window = new PairingApprovalWindow(candidate);
            var registration = cancellationToken.Register(() =>
            {
                var lapsed = completion.TrySetCanceled(cancellationToken);
                _ = window.Dispatcher.InvokeAsync(() =>
                {
                    window.Close();
                    if (lapsed)
                    {
                        onRequestTimedOut?.Invoke();
                    }
                });
            });
            window.Closed += (_, _) =>
            {
                registration.Dispose();
                completion.TrySetResult(window.Approved);
            };

            try
            {
                window.Show();
            }
            catch (InvalidOperationException)
            {
                // Application shutdown won the race: no window can open, so nobody can
                // approve. Closed never fires for a window that never showed.
                registration.Dispose();
                LocalDiagnostics.Write("pairing_approval_show_refused");
                completion.TrySetResult(false);
                return;
            }

            window.Activate();
            FlashUntilForeground(window);
            onRequestShown?.Invoke(NoticeName(candidate.DisplayName));
        });
        return completion.Task;
    }

    /// <summary>
    /// Flashes the taskbar button and caption until the window comes to the foreground
    /// (FLASHW_TIMERNOFG stops on its own). Activate() alone is refused when another process
    /// owns the foreground, which is exactly the situation while the user watches the phone.
    /// </summary>
    private static void FlashUntilForeground(PairingApprovalWindow window)
    {
        var handle = new WindowInteropHelper(window).Handle;
        if (handle == nint.Zero)
        {
            return;
        }

        var info = new NativeMethods.FlashInfo
        {
            Size = (uint)Marshal.SizeOf<NativeMethods.FlashInfo>(),
            Window = handle,
            Flags = NativeMethods.FlashAll | NativeMethods.FlashTimerUntilForeground,
            Count = 0,
            Timeout = 0
        };
        _ = NativeMethods.FlashWindowEx(ref info);
    }

    /// <summary>The name as it may appear in a balloon: trimmed, cut with an ellipsis, never split inside a surrogate pair.</summary>
    internal static string NoticeName(string displayName)
    {
        var trimmed = displayName.Trim();
        if (trimmed.Length <= NoticeNameMaxLength)
        {
            return trimmed;
        }

        var cut = NoticeNameMaxLength - 1;
        if (char.IsHighSurrogate(trimmed[cut - 1]))
        {
            cut--;
        }

        return string.Concat(trimmed.AsSpan(0, cut), "…");
    }

    private static class NativeMethods
    {
        internal const uint FlashAll = 0x00000003;

        internal const uint FlashTimerUntilForeground = 0x0000000C;

        [StructLayout(LayoutKind.Sequential)]
        internal struct FlashInfo
        {
            public uint Size;
            public nint Window;
            public uint Flags;
            public uint Count;
            public uint Timeout;
        }

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool FlashWindowEx(ref FlashInfo info);
    }
}
