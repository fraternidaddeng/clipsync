using System.Net.WebSockets;
using System.Text;
using ClipSync.Core.Protocol;
using ClipSync.Peer.Transport;

namespace ClipSync.Tests.Peer;

public sealed class WebSocketSyncTransportTests
{
    [Fact]
    public async Task AssembledMessageOverTheCapIsTooLarge()
    {
        var socket = ScriptedWebSocket.TextFragments("aaaaa", "bbbbbb");
        await using var transport = new WebSocketSyncTransport(socket, maxTextMessageBytes: 10);

        Assert.IsType<TransportFrame.TooLarge>(await transport.ReceiveAsync(CancellationToken.None));
    }

    [Fact]
    public async Task AssembledMessageAtTheCapIsText()
    {
        var socket = ScriptedWebSocket.TextFragments("aaaa", "bbbbbb");
        await using var transport = new WebSocketSyncTransport(socket, maxTextMessageBytes: 10);

        var frame = Assert.IsType<TransportFrame.Text>(await transport.ReceiveAsync(CancellationToken.None));
        Assert.Equal("aaaabbbbbb", frame.Payload);
    }

    [Fact]
    public async Task SingleReceiveBufferCannotBypassTheCap()
    {
        var socket = ScriptedWebSocket.TextFragments(new string('x', 64));
        await using var transport = new WebSocketSyncTransport(socket, maxTextMessageBytes: 32);

        Assert.IsType<TransportFrame.TooLarge>(await transport.ReceiveAsync(CancellationToken.None));
    }

    [Fact]
    public async Task CloseFrameIsClosed()
    {
        var socket = ScriptedWebSocket.Close();
        await using var transport = new WebSocketSyncTransport(socket, maxTextMessageBytes: 32);

        Assert.IsType<TransportFrame.Closed>(await transport.ReceiveAsync(CancellationToken.None));
    }

    [Fact]
    public async Task BinaryFrameIsBinary()
    {
        var socket = ScriptedWebSocket.Binary(new byte[] { 1, 2, 3 });
        await using var transport = new WebSocketSyncTransport(socket, maxTextMessageBytes: 32);

        Assert.IsType<TransportFrame.Binary>(await transport.ReceiveAsync(CancellationToken.None));
    }

    /// <summary>
    /// Minimal scripted socket so the assembler can be tested without a live listener.
    /// Payloads are test-controlled and never clipboard content.
    /// </summary>
    private sealed class ScriptedWebSocket : WebSocket
    {
        private readonly Queue<WebSocketReceiveResult> results = new();
        private readonly Queue<byte[]> payloads = new();
        private WebSocketState state = WebSocketState.Open;

        public static ScriptedWebSocket TextFragments(params string[] fragments)
        {
            var socket = new ScriptedWebSocket();
            for (var index = 0; index < fragments.Length; index++)
            {
                var bytes = Encoding.UTF8.GetBytes(fragments[index]);
                socket.payloads.Enqueue(bytes);
                socket.results.Enqueue(new WebSocketReceiveResult(
                    bytes.Length,
                    WebSocketMessageType.Text,
                    endOfMessage: index == fragments.Length - 1));
            }

            return socket;
        }

        public static ScriptedWebSocket Binary(byte[] payload)
        {
            var socket = new ScriptedWebSocket();
            socket.payloads.Enqueue(payload);
            socket.results.Enqueue(new WebSocketReceiveResult(payload.Length, WebSocketMessageType.Binary, true));
            return socket;
        }

        public static ScriptedWebSocket Close()
        {
            var socket = new ScriptedWebSocket();
            socket.payloads.Enqueue([]);
            socket.results.Enqueue(new WebSocketReceiveResult(0, WebSocketMessageType.Close, true));
            return socket;
        }

        public override WebSocketCloseStatus? CloseStatus => null;

        public override string? CloseStatusDescription => null;

        public override string? SubProtocol => null;

        public override WebSocketState State => state;

        public override void Abort() => state = WebSocketState.Aborted;

        public override Task CloseAsync(
            WebSocketCloseStatus closeStatus,
            string? statusDescription,
            CancellationToken cancellationToken)
        {
            state = WebSocketState.Closed;
            return Task.CompletedTask;
        }

        public override Task CloseOutputAsync(
            WebSocketCloseStatus closeStatus,
            string? statusDescription,
            CancellationToken cancellationToken)
        {
            state = WebSocketState.CloseSent;
            return Task.CompletedTask;
        }

        public override void Dispose() => state = WebSocketState.Closed;

        public override Task<WebSocketReceiveResult> ReceiveAsync(
            ArraySegment<byte> buffer,
            CancellationToken cancellationToken)
        {
            if (results.Count == 0)
            {
                return Task.FromResult(new WebSocketReceiveResult(0, WebSocketMessageType.Close, true));
            }

            var payload = payloads.Dequeue();
            var result = results.Dequeue();
            payload.AsSpan().CopyTo(buffer.AsSpan());
            return Task.FromResult(result);
        }

        public override Task SendAsync(
            ArraySegment<byte> buffer,
            WebSocketMessageType messageType,
            bool endOfMessage,
            CancellationToken cancellationToken) => Task.CompletedTask;
    }
}
