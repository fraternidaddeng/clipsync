using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;

namespace ClipSync.Tests.Peer;

/// <summary>
/// These tests swap the process-wide default proxy, so nothing else may run beside them.
/// </summary>
[CollectionDefinition("global proxy environment", DisableParallelization = true)]
public sealed class GlobalProxyEnvironmentDefinition
{
}

/// <summary>
/// A fake system proxy: a live listener that records and refuses every TCP connection,
/// exposed as an <see cref="IWebProxy"/> that routes every URI to it. A custom fake rather
/// than <see cref="WebProxy"/> because WebProxy always bypasses loopback, while a real
/// global-mode proxy intercepts LAN destinations exactly like this.
/// </summary>
internal sealed class RecordingFakeProxy : IWebProxy, IAsyncDisposable
{
    private readonly TcpListener listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource stop = new();
    private readonly Task acceptLoop;
    private int connections;

    public RecordingFakeProxy()
    {
        listener.Start();
        acceptLoop = AcceptAndRefuseAsync();
    }

    /// <summary>How many TCP connections actually reached the fake proxy.</summary>
    public int ConnectionCount => Volatile.Read(ref connections);

    public ICredentials? Credentials { get; set; }

    public Uri? GetProxy(Uri destination) =>
        new($"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}");

    /// <summary>Never bypass anything, loopback included, so the poison is total.</summary>
    public bool IsBypassed(Uri host) => false;

    public async ValueTask DisposeAsync()
    {
        await stop.CancelAsync();
        listener.Stop();
        try
        {
            await acceptLoop.WaitAsync(TimeSpan.FromSeconds(5));
        }
        catch (TimeoutException)
        {
        }

        stop.Dispose();
    }

    private async Task AcceptAndRefuseAsync()
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                // Closing without answering refuses the CONNECT: a proxy on the peer path
                // must visibly break the tunnel, never silently pass traffic through.
                using var socket = await listener.AcceptSocketAsync(stop.Token);
                Interlocked.Increment(ref connections);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (SocketException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }
}

/// <summary>
/// Proves peer sync dials the paired host directly even when a system proxy is configured:
/// peers are private LAN or Tailscale addresses that a global proxy (WinINet settings,
/// HTTPS_PROXY/ALL_PROXY, Clash/Surge in system-proxy mode) can neither reach nor be
/// allowed to observe. PeerSyncClient sets ClientWebSocket.Options.Proxy to null for that.
/// </summary>
[Collection("global proxy environment")]
public sealed class PeerProxyBypassTests
{
    [Fact]
    public async Task SyncDialsDirectlyEvenWhenAGlobalProxyIsConfigured()
    {
        await using var pair = await PeerPair.CreateAsync();
        await using var proxy = new RecordingFakeProxy();

        // HttpClient.DefaultProxy is what HTTPS_PROXY/ALL_PROXY or the WinINet proxy
        // settings resolve to at runtime, and it is exactly what ClientWebSocket consults
        // when Options.Proxy is left at its default.
        var previous = HttpClient.DefaultProxy;
        HttpClient.DefaultProxy = proxy;
        try
        {
            var session = await pair.DialAsync();
            await pair.WaitUntilAsync(() => Task.FromResult(pair.Server.ConnectedDeviceCount == 1));
            await session.CloseAsync();
        }
        finally
        {
            HttpClient.DefaultProxy = previous;
        }

        Assert.Equal(0, proxy.ConnectionCount);
    }

    [Fact]
    public async Task ASocketThatKeepsTheSystemProxyIsCapturedByIt()
    {
        // Contrast case proving the harness bites: a socket that keeps a system proxy (here
        // injected explicitly, exactly what the default options resolve to) is routed into
        // the fake proxy and the refused tunnel fails the dial. Peer sync must never look
        // like this, hence Options.Proxy = null in PeerSyncClient.
        await using var proxy = new RecordingFakeProxy();
        using var socket = new ClientWebSocket();
        socket.Options.Proxy = proxy;
        socket.Options.SetRequestHeader("X-Protocol-Version", "1");

        // No certificate callback: the refused CONNECT fails the dial before TLS starts.
        await Assert.ThrowsAsync<WebSocketException>(() => socket.ConnectAsync(
            new Uri("wss://192.0.2.1:47654/v1/peer/sync"),
            CancellationToken.None));

        Assert.True(proxy.ConnectionCount >= 1, "the poisoned proxy never saw the dial");
    }
}
