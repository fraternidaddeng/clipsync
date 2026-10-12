using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Peer.Client;
using ClipSync.Peer.Sessions;
using ClipSync.Peer.Transport;

namespace ClipSync.Tests.Peer;

public sealed class SyncFetchRecoveryTests
{
    [Fact]
    public async Task FetchRejectedByPauseShouldRecoverAfterResumeWithoutReconnect()
    {
        var allowed = true;
        await using var pair = await PeerPair.CreateAsync(serverSessionOptions: PeerPair.DefaultSessionOptions() with
        {
            ProtocolVersion = 1,
            OutboundAllowed = () => Volatile.Read(ref allowed)
        });
        await PeerPair.CaptureAsync(pair.WindowsStore, "rejected-fetch");
        var socket = await PeerSyncClient.ConnectAsync("127.0.0.1", pair.Server.Port, pair.ServerFingerprint, 1, CancellationToken.None);
        var intercept = new PauseBeforeFetchTransport(socket, () => Volatile.Write(ref allowed, false));
        using var receiver = new SyncSessionEngine(SyncSessionRole.Dialer, pair.AndroidStore, pair.Protector,
            PeerPair.DialerOptions() with { ProtocolVersion = 1 });
        var run = receiver.RunAsync(intercept, CancellationToken.None);
        try
        {
            await intercept.ResponseObserved.Task.WaitAsync(TimeSpan.FromSeconds(10));
            Volatile.Write(ref allowed, true);
            await pair.WaitUntilAsync(async () => (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains("rejected-fetch"));
            Assert.False(run.IsCompleted);
            Assert.Contains("rejected-fetch", await PeerPair.VisibleTextsAsync(pair.AndroidStore));
        }
        finally
        {
            receiver.RequestClose();
            await run.WaitAsync(TimeSpan.FromSeconds(10));
        }
    }

    [Fact]
    public async Task NewlyQueuedDeletionOfAnOldClipShouldSurviveCleanupAndReachItsPeer()
    {
        await using var pair = await PeerPair.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        const string text = "old-but-synced";
        var bytes = Encoding.UTF8.GetBytes(text);
        var stored = await pair.WindowsStore.StoreAsync(new AcceptedClipboardContent(
            text, Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(), bytes.Length, "test", now.AddDays(-40)));
        var first = await pair.DialAsync();
        await pair.WaitUntilAsync(async () => (await PeerPair.VisibleTextsAsync(pair.AndroidStore)).Contains(text));
        await pair.WaitUntilAsync(async () => (await pair.WindowsStore.GetOutboxStatusAsync()).PendingCount == 0);
        await first.CloseAsync();

        Assert.True(await pair.WindowsStore.DeleteAsync(stored.EventId, now));
        Assert.Single(await pair.WindowsStore.GetOutboxBatchAsync(PeerPair.AndroidDeviceId, 10));
        await pair.WindowsStore.CleanupAsync(new ClipboardRetentionPolicy(maximumAge: TimeSpan.FromDays(30)), now);
        var second = await pair.DialAsync();
        await pair.WaitUntilAsync(async () => await pair.AndroidStore.GetByIdAsync(stored.EventId) is null);
        var remote = await pair.AndroidStore.GetByIdAsync(stored.EventId);
        Assert.True(remote is null || remote.IsDeleted, "The newly queued deletion was dropped by created_at TTL; peer retains its body after reconnect.");
        await second.CloseAsync();
    }

    private sealed class PauseBeforeFetchTransport(ISyncTransport inner, Action pause) : ISyncTransport
    {
        private bool intercepted;
        public TaskCompletionSource ResponseObserved { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public async ValueTask<TransportFrame> ReceiveAsync(CancellationToken cancellationToken)
        {
            var frame = await inner.ReceiveAsync(cancellationToken);
            if (frame is TransportFrame.Text text && ProtocolReader.Parse(text.Payload) is ProtocolParseOutcome.Success parsed
                && (parsed.Body is ErrorBody { Code: ProtocolErrorCodes.PayloadNotFound } || parsed.Type == ProtocolMessageTypes.ClipPayload))
            {
                ResponseObserved.TrySetResult();
            }
            return frame;
        }

        public ValueTask SendTextAsync(string payload, CancellationToken cancellationToken)
        {
            if (!intercepted && ProtocolReader.Parse(payload) is ProtocolParseOutcome.Success { Type: ProtocolMessageTypes.ClipFetch })
            {
                intercepted = true;
                pause();
            }
            return inner.SendTextAsync(payload, cancellationToken);
        }

        public ValueTask CloseAsync(WebSocketCloseStatus status, string description, CancellationToken cancellationToken) =>
            inner.CloseAsync(status, description, cancellationToken);
        public ValueTask DisposeAsync() => inner.DisposeAsync();
    }
}
