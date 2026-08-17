using System.Windows.Threading;
using ClipSync.Peer.Pairing;

namespace ClipSync.App.Pairing;

/// <summary>
/// Bridges the confirm endpoint (running on a Kestrel worker thread) to a WPF approval
/// window. Cancellation from the approval timeout closes the window and surfaces as
/// PAIRING_TIMEOUT rather than a rejection.
/// </summary>
public sealed class WpfPairingApprover(Dispatcher dispatcher) : IPairingApprover
{
    public Task<bool> ApproveAsync(PairingCandidate candidate, CancellationToken cancellationToken)
    {
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = dispatcher.InvokeAsync(() =>
        {
            var window = new PairingApprovalWindow(candidate);
            var registration = cancellationToken.Register(() =>
            {
                completion.TrySetCanceled(cancellationToken);
                _ = window.Dispatcher.InvokeAsync(window.Close);
            });
            window.Closed += (_, _) =>
            {
                registration.Dispose();
                completion.TrySetResult(window.Approved);
            };
            window.Show();
            window.Activate();
        });
        return completion.Task;
    }
}
