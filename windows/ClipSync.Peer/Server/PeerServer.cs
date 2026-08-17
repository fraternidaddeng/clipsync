using System.Collections.Concurrent;
using System.Net;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Peer.Diagnostics;
using ClipSync.Peer.Pairing;
using ClipSync.Peer.Sessions;
using ClipSync.Peer.Transport;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipSync.Peer.Server;

public sealed record PeerServerOptions
{
    public required X509Certificate2 Certificate { get; init; }

    public required SyncSessionOptions SessionOptions { get; init; }

    /// <summary>Addresses to bind; defaults to loopback only. The App layer adds LAN addresses.</summary>
    public IReadOnlyList<IPAddress> BindAddresses { get; init; } = [IPAddress.Loopback];

    /// <summary>0 selects an ephemeral port; read the result from <see cref="PeerServer.Port"/>.</summary>
    public int Port { get; init; }

    public int MaxConcurrentSessions { get; init; } = 8;
}

/// <summary>
/// The HTTPS/WebSocket listener half of the peer endpoint: TLS with the pinned self-signed
/// certificate, protocol version gate, health endpoint, and one sync session per socket.
/// </summary>
public sealed class PeerServer : IAsyncDisposable
{
    private readonly SqliteClipboardEventStore store;
    private readonly ISecretProtector secretProtector;
    private readonly PeerServerOptions options;
    private readonly ILoggerFactory loggerFactory;
    private readonly ILogger logger;
    private readonly AuthThrottle authThrottle;
    private readonly PairingService? pairing;
    private readonly ConcurrentDictionary<Guid, ActiveSession> sessions = new();
    private WebApplication? app;

    public PeerServer(
        SqliteClipboardEventStore store,
        ISecretProtector secretProtector,
        PeerServerOptions options,
        ILoggerFactory? loggerFactory = null,
        PairingService? pairingService = null)
    {
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.secretProtector = secretProtector ?? throw new ArgumentNullException(nameof(secretProtector));
        this.options = options ?? throw new ArgumentNullException(nameof(options));
        this.loggerFactory = loggerFactory ?? NullLoggerFactory.Instance;
        logger = this.loggerFactory.CreateLogger("ClipSync.Peer.Server");
        authThrottle = new AuthThrottle(options.SessionOptions.TimeProvider);
        pairing = pairingService;
    }

    /// <summary>Raised when any session commits remote clip bodies locally.</summary>
    public event Action<IReadOnlyList<RemoteClipApplied>>? RemoteClipsCommitted;

    public int Port { get; private set; }

    public int ActiveSessionCount => sessions.Count;

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (app is not null)
        {
            throw new InvalidOperationException("The server is already running.");
        }

        var builder = WebApplication.CreateBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(kestrel =>
        {
            foreach (var address in options.BindAddresses)
            {
                kestrel.Listen(address, options.Port, listen =>
                {
                    listen.UseHttps(https =>
                    {
                        https.ServerCertificate = options.Certificate;
                        https.SslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13;
                    });
                });
            }
        });

        var host = builder.Build();
        host.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(30) });

        host.Use(async (context, next) =>
        {
            var version = context.Request.Headers["X-Protocol-Version"];
            if (version.Count != 1 || version[0] != "1")
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                await context.Response.WriteAsJsonAsync(new { error = ProtocolErrorCodes.UnsupportedVersion });
                return;
            }

            await next();
        });

        host.MapGet("/v1/peer/health", () => Results.Json(new
        {
            version = ProtocolLimits.ProtocolVersion,
            device_id = store.LocalDeviceId,
            port = Port
        }));

        host.Map("/v1/peer/sync", HandleSyncAsync);

        if (pairing is not null)
        {
            host.MapPost("/v1/pair/confirm", HandlePairConfirmAsync);
        }

        await host.StartAsync(cancellationToken).ConfigureAwait(false);
        app = host;
        Port = ResolveBoundPort(host);
        PeerLog.ServerListening(logger, Port, options.BindAddresses.Count);
    }

    private async Task HandleSyncAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        if (sessions.Count >= options.MaxConcurrentSessions)
        {
            PeerLog.SessionLimitReached(logger, options.MaxConcurrentSessions);
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }

        using var socket = await context.WebSockets.AcceptWebSocketAsync().ConfigureAwait(false);
        var engine = new SyncSessionEngine(
            SyncSessionRole.Listener,
            store,
            secretProtector,
            options.SessionOptions,
            authThrottle,
            loggerFactory.CreateLogger("ClipSync.Peer.Session"));
        engine.RemoteClipsCommitted += OnRemoteClipsCommitted;

        var sessionId = Guid.NewGuid();
        var active = new ActiveSession(engine);
        sessions[sessionId] = active;
        try
        {
            await using var transport = new WebSocketSyncTransport(socket);
            await engine.RunAsync(transport, context.RequestAborted).ConfigureAwait(false);
        }
        finally
        {
            engine.RemoteClipsCommitted -= OnRemoteClipsCommitted;
            sessions.TryRemove(sessionId, out _);
        }
    }

    private async Task HandlePairConfirmAsync(HttpContext context)
    {
        // The version middleware already enforced X-Protocol-Version. Read at most the
        // document limit plus one byte; anything longer is rejected without buffering it.
        var buffer = new byte[PairingJson.MaxDocumentBytes + 1];
        var total = 0;
        while (total < buffer.Length)
        {
            var read = await context.Request.Body
                .ReadAsync(buffer.AsMemory(total, buffer.Length - total), context.RequestAborted)
                .ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            total += read;
        }

        if (total > PairingJson.MaxDocumentBytes)
        {
            await WritePairingErrorAsync(context, 400, PairingErrorCodes.SchemaViolation).ConfigureAwait(false);
            return;
        }

        var request = PairingJson.ParseConfirmRequest(buffer.AsSpan(0, total), out _);
        if (request is null)
        {
            await WritePairingErrorAsync(context, 400, PairingErrorCodes.SchemaViolation).ConfigureAwait(false);
            return;
        }

        var outcome = await pairing!.ConfirmAsync(request, context.RequestAborted).ConfigureAwait(false);
        if (outcome is PairingConfirmOutcome.Approved approved)
        {
            context.Response.StatusCode = StatusCodes.Status200OK;
            context.Response.ContentType = "application/json";
            await context.Response.WriteAsync(PairingJson.Serialize(approved.Response), context.RequestAborted).ConfigureAwait(false);
            return;
        }

        var failed = (PairingConfirmOutcome.Failed)outcome;
        await WritePairingErrorAsync(context, failed.HttpStatus, failed.ErrorCode).ConfigureAwait(false);
    }

    private static async Task WritePairingErrorAsync(HttpContext context, int status, string code)
    {
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json";
        var body = new PairingErrorBody
        {
            Kind = PairingDocumentKinds.Error,
            Version = ProtocolLimits.ProtocolVersion,
            Error = code
        };
        await context.Response.WriteAsync(PairingJson.Serialize(body), context.RequestAborted).ConfigureAwait(false);
    }

    private void OnRemoteClipsCommitted(IReadOnlyList<RemoteClipApplied> batch) =>
        RemoteClipsCommitted?.Invoke(batch);

    /// <summary>Terminates live sessions for a device, e.g. right after revocation.</summary>
    public void DisconnectDevice(string deviceId)
    {
        foreach (var session in sessions.Values)
        {
            if (string.Equals(session.Engine.PeerDeviceId, deviceId, StringComparison.Ordinal))
            {
                session.Engine.RequestClose();
            }
        }
    }

    private static int ResolveBoundPort(WebApplication host)
    {
        var feature = ((IServer)host.Services.GetRequiredService<IServer>()).Features.Get<IServerAddressesFeature>();
        var first = feature?.Addresses.FirstOrDefault();
        return first is null ? 0 : new Uri(first).Port;
    }

    public async ValueTask DisposeAsync()
    {
        foreach (var session in sessions.Values)
        {
            session.Engine.RequestClose();
        }

        if (app is not null)
        {
            await app.StopAsync().ConfigureAwait(false);
            await app.DisposeAsync().ConfigureAwait(false);
            app = null;
        }
    }

    private sealed record ActiveSession(SyncSessionEngine Engine);
}
