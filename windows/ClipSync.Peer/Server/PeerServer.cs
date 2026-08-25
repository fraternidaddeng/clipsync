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
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipSync.Peer.Server;

/// <summary>
/// Vocabulary of the health endpoint's <c>clipboard_apply_text</c> field: the listener's
/// honest self-report of whether remote text actually reaches its clipboard. Posture states
/// ("off", "paused") are user choices; the rest is evidence from the most recent real apply
/// this session — never "the API exists so it works".
/// </summary>
public static class ClipboardApplyStates
{
    /// <summary>自动写入 is switched off: remote text lands in history only.</summary>
    public const string Off = "off";

    /// <summary>Sync is paused: inbound still stores to history but never auto-applies.</summary>
    public const string Paused = "paused";

    /// <summary>Auto-apply is on but no remote text has been applied yet this session.</summary>
    public const string Unverified = "unverified";

    /// <summary>The most recent remote text apply reached the system clipboard.</summary>
    public const string Applied = "applied";

    /// <summary>The most recent remote text apply threw; content stayed in history.</summary>
    public const string Failed = "failed";
}

public sealed record PeerServerOptions
{
    public required X509Certificate2 Certificate { get; init; }

    public required SyncSessionOptions SessionOptions { get; init; }

    /// <summary>
    /// Live self-report for the health endpoint's <c>clipboard_apply_text</c> field (one of
    /// <see cref="ClipboardApplyStates"/>). Re-read per request so posture toggles apply
    /// immediately. Null (or a null return) omits the field entirely; peers must read the
    /// absence as "not reported", never as bad news.
    /// </summary>
    public Func<string?>? ClipboardApplyState { get; init; }

    /// <summary>Addresses to bind; defaults to loopback only. The App layer adds LAN addresses.</summary>
    public IReadOnlyList<IPAddress> BindAddresses { get; init; } = [IPAddress.Loopback];

    /// <summary>0 selects an ephemeral port; read the result from <see cref="PeerServer.Port"/>.</summary>
    public int Port { get; init; }

    public int MaxConcurrentSessions { get; init; } = 8;

    public const int DefaultPairingConfirmsPerWindow = 10;

    public const int DefaultSyncAcceptsPerWindow = 30;

    /// <summary>Pairing confirm attempts admitted per remote address per <see cref="ConnectionRateLimitWindow"/>.</summary>
    public int MaxPairingConfirmsPerWindow { get; init; } = DefaultPairingConfirmsPerWindow;

    /// <summary>WebSocket accepts admitted per remote address per <see cref="ConnectionRateLimitWindow"/>.</summary>
    public int MaxSyncAcceptsPerWindow { get; init; } = DefaultSyncAcceptsPerWindow;

    public TimeSpan ConnectionRateLimitWindow { get; init; } = TimeSpan.FromMinutes(1);
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
    private readonly SlidingWindowRateLimiter pairingConfirmLimiter;
    private readonly SlidingWindowRateLimiter syncAcceptLimiter;
    private readonly PairingService? pairing;
    private readonly ConcurrentDictionary<Guid, ActiveSession> sessions = new();
    private WebApplication? app;
    private bool refusingNewSessions;

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
        var clock = options.SessionOptions.TimeProvider;
        authThrottle = new AuthThrottle(clock);
        authThrottle.DeviceLockedOut += OnDeviceLockedOut;
        pairingConfirmLimiter = new SlidingWindowRateLimiter(
            clock,
            options.MaxPairingConfirmsPerWindow,
            options.ConnectionRateLimitWindow);
        syncAcceptLimiter = new SlidingWindowRateLimiter(
            clock,
            options.MaxSyncAcceptsPerWindow,
            options.ConnectionRateLimitWindow);
        pairing = pairingService;
    }

    /// <summary>Raised when any session commits remote clip bodies locally.</summary>
    public event Action<IReadOnlyList<RemoteClipApplied>>? RemoteClipsCommitted;

    /// <summary>
    /// Raised on a worker thread when a device first crosses the failed-auth rate limit. The App
    /// layer surfaces this (diagnostics entry, tray notice); the payload is the claimed device id.
    /// </summary>
    public event Action<string>? DeviceLockedOut;

    /// <summary>
    /// Raised on a worker thread whenever the set of live sessions changes: a session
    /// finished its handshake or a session ended. Subscribers read
    /// <see cref="ConnectedDeviceIds"/> for the current authenticated set.
    /// </summary>
    public event Action? SessionsChanged;

    public int Port { get; private set; }

    public int ActiveSessionCount => sessions.Count;

    /// <summary>Distinct device ids with a session past the handshake right now.</summary>
    public IReadOnlyList<string> ConnectedDeviceIds => sessions.Values
        .Where(session => session.Engine.IsReady)
        .Select(session => session.Engine.PeerDeviceId)
        .OfType<string>()
        .Distinct(StringComparer.Ordinal)
        .ToArray();

    public int ConnectedDeviceCount => ConnectedDeviceIds.Count;

    /// <summary>Claimed device ids that are rate-limited right now; empty when none.</summary>
    public IReadOnlyList<string> ThrottledDeviceIds => authThrottle.ThrottledDevices();

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
                        // CA5398 wants SslProtocols.None (OS picks). We pin 1.2+ so TLS 1.0/1.1 cannot come back.
#pragma warning disable CA5398
                        https.SslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13;
#pragma warning restore CA5398
                    });
                });
            }
        });

        var host = builder.Build();
        host.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(30) });

        host.Use(async (context, next) =>
        {
            var version = context.Request.Headers["X-Protocol-Version"];
            var path = context.Request.Path.Value ?? string.Empty;
            var expected = path.StartsWith("/v2/", StringComparison.Ordinal)
                ? "2"
                : path.StartsWith("/v1/", StringComparison.Ordinal)
                    ? "1"
                    : null;
            if (expected is null)
            {
                await next();
                return;
            }

            if (version.Count != 1 || version[0] != expected)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                await context.Response.WriteAsJsonAsync(new { error = ProtocolErrorCodes.UnsupportedVersion });
                return;
            }

            await next();
        });

        host.MapGet("/v1/peer/health", () =>
        {
            // The apply posture lets the phone's 对端写入 segment state facts instead of
            // sitting on 未探测 forever. Status words only — never clipboard content.
            var applyState = options.ClipboardApplyState?.Invoke();
            return applyState is null
                ? Results.Json(new
                {
                    version = ProtocolLimits.ProtocolVersion,
                    device_id = store.LocalDeviceId,
                    port = Port
                })
                : Results.Json(new
                {
                    version = ProtocolLimits.ProtocolVersion,
                    device_id = store.LocalDeviceId,
                    port = Port,
                    clipboard_apply_text = applyState
                });
        });

        host.Map("/v1/peer/sync", context => HandleSyncAsync(context, ProtocolLimits.ProtocolVersion));
        host.Map("/v2/peer/sync", context => HandleSyncAsync(context, ProtocolLimits.ProtocolVersionV2));

        if (pairing is not null)
        {
            host.MapPost("/v1/pair/confirm", HandlePairConfirmAsync);
        }

        await host.StartAsync(cancellationToken).ConfigureAwait(false);
        app = host;
        Port = ResolveBoundPort(host);
        PeerLog.ServerListening(logger, Port, options.BindAddresses.Count);
    }

    private async Task HandleSyncAsync(HttpContext context, int protocolVersion)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        // Pre-auth, per-remote-address admission: a flood from one address exhausts its own
        // budget before it can occupy handshake slots. Checked before the socket upgrade so
        // refused dials cost one HTTP response, not a WebSocket accept.
        if (!syncAcceptLimiter.TryAdmit(RemoteKey(context)))
        {
            PeerLog.ConnectionRateLimited(logger, "sync_accept");
            context.Response.StatusCode = StatusCodes.Status429TooManyRequests;
            await context.Response.WriteAsJsonAsync(new { error = ProtocolErrorCodes.RateLimited });
            return;
        }

        if (protocolVersion == ProtocolLimits.ProtocolVersionV2 && !options.SessionOptions.ImageSyncEnabled())
        {
            // Image sync is off locally: do not advertise image_clip_v2 by accepting the
            // /v2 route (protocol v2 §3 requires both peers to opt in before image bodies
            // may flow). Refusing before the upgrade makes the dialer fall back to
            // /v1/peer/sync, so text sync continues on the frozen v1 contract.
            PeerLog.V2RefusedImageSyncDisabled(logger);
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            await context.Response.WriteAsJsonAsync(new { error = ProtocolErrorCodes.UnsupportedMedia });
            return;
        }

        if (Volatile.Read(ref refusingNewSessions))
        {
            // Suspending: the machine is about to sleep. Refusing here keeps a
            // fast Android redial from opening a session that would die half-open
            // moments later; the resume recovery pass invites peers back.
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }

        if (sessions.Count >= options.MaxConcurrentSessions)
        {
            PeerLog.SessionLimitReached(logger, options.MaxConcurrentSessions);
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }

        using var socket = await context.WebSockets.AcceptWebSocketAsync().ConfigureAwait(false);
        // The dialer picks the contract by path: /v1 keeps the frozen text protocol,
        // /v2 enables image_clip_v2 for this session only.
        var sessionOptions = options.SessionOptions with { ProtocolVersion = protocolVersion };
        var engine = new SyncSessionEngine(
            SyncSessionRole.Listener,
            store,
            secretProtector,
            sessionOptions,
            authThrottle,
            loggerFactory.CreateLogger("ClipSync.Peer.Session"));
        engine.RemoteClipsCommitted += OnRemoteClipsCommitted;
        engine.SessionReady += OnSessionReady;

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
            engine.SessionReady -= OnSessionReady;
            sessions.TryRemove(sessionId, out _);
            SessionsChanged?.Invoke();
        }
    }

    private void OnSessionReady(string deviceId) => SessionsChanged?.Invoke();

    private void OnDeviceLockedOut(string deviceId)
    {
        PeerLog.AuthRateLimited(logger, deviceId);
        DeviceLockedOut?.Invoke(deviceId);
    }

    private async Task HandlePairConfirmAsync(HttpContext context)
    {
        if (!pairingConfirmLimiter.TryAdmit(RemoteKey(context)))
        {
            PeerLog.ConnectionRateLimited(logger, "pairing_confirm");
            await WritePairingErrorAsync(context, StatusCodes.Status429TooManyRequests, PairingErrorCodes.RateLimited)
                .ConfigureAwait(false);
            return;
        }

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

    private static string RemoteKey(HttpContext context) =>
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown";

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

    /// <summary>Cancels every live session without stopping the listener (e.g. before sleep).</summary>
    public void DisconnectAllSessions()
    {
        foreach (var session in sessions.Values)
        {
            session.Engine.RequestClose();
        }
    }

    /// <summary>
    /// Gate for the suspend window: while true, new sync sessions are refused
    /// with 503 so peers cannot open half-alive connections right before sleep.
    /// </summary>
    public void SetRefuseNewSessions(bool refuse) => Volatile.Write(ref refusingNewSessions, refuse);

    private static int ResolveBoundPort(WebApplication host)
    {
        var feature = ((IServer)host.Services.GetRequiredService<IServer>()).Features.Get<IServerAddressesFeature>();
        var first = feature?.Addresses.FirstOrDefault();
        return first is null ? 0 : new Uri(first).Port;
    }

    public async ValueTask DisposeAsync()
    {
        authThrottle.DeviceLockedOut -= OnDeviceLockedOut;
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
