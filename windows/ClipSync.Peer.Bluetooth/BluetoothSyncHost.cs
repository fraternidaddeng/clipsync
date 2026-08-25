using System.Security.Cryptography;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;
using ClipSync.Core.Security.Bt1;
using ClipSync.Core.Storage;
using ClipSync.Peer.Server;
using ClipSync.Peer.Sessions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipSync.Peer.Bluetooth;

public sealed record BluetoothSyncHostOptions
{
    public required SyncSessionOptions SessionOptions { get; init; }

    /// <summary>Covers the whole four-message bt1 handshake (docs/protocol-bt1.md section 3).</summary>
    public TimeSpan HandshakeTimeout { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>Pause after the RFCOMM server faults before trying to restart it.</summary>
    public TimeSpan RestartDelay { get; init; } = TimeSpan.FromSeconds(15);

    /// <summary>Inbound RFCOMM accepts admitted per remote address per minute, matching the IP listener's budget.</summary>
    public int MaxAcceptsPerWindow { get; init; } = PeerServerOptions.DefaultSyncAcceptsPerWindow;
}

/// <summary>
/// The Windows side of the Bluetooth fallback (ADR 0005 phase 2): accepts RFCOMM
/// connections from the platform seam, runs the bt1 listener handshake against the stored
/// pairings, and hands the encrypted channel to an unmodified protocol v1
/// <see cref="SyncSessionEngine"/> session. One session at a time — the fallback serves
/// exactly one phone while the LAN is unreachable, it is not a second peer server. Inner
/// sessions are always protocol v1: ADR 0005 section 4 forbids image_clip_v2 over Bluetooth.
/// </summary>
public sealed class BluetoothSyncHost : IAsyncDisposable
{
    private readonly SqliteClipboardEventStore store;
    private readonly ISecretProtector secretProtector;
    private readonly IRfcommServer server;
    private readonly BluetoothSyncHostOptions options;
    private readonly ILogger logger;
    private readonly ILoggerFactory loggerFactory;
    private readonly AuthThrottle authThrottle;
    private readonly SlidingWindowRateLimiter acceptLimiter;
    private readonly object sessionGate = new();
    private CancellationTokenSource? loopCts;
    private Task? acceptLoop;
    private SyncSessionEngine? activeEngine;
    private string? activeDeviceId;

    public BluetoothSyncHost(
        SqliteClipboardEventStore store,
        ISecretProtector secretProtector,
        IRfcommServer server,
        BluetoothSyncHostOptions options,
        ILoggerFactory? loggerFactory = null)
    {
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.secretProtector = secretProtector ?? throw new ArgumentNullException(nameof(secretProtector));
        this.server = server ?? throw new ArgumentNullException(nameof(server));
        this.options = options ?? throw new ArgumentNullException(nameof(options));
        this.loggerFactory = loggerFactory ?? NullLoggerFactory.Instance;
        logger = this.loggerFactory.CreateLogger("ClipSync.Peer.Bluetooth");
        var clock = options.SessionOptions.TimeProvider;
        authThrottle = new AuthThrottle(clock);
        authThrottle.DeviceLockedOut += OnDeviceLockedOut;
        acceptLimiter = new SlidingWindowRateLimiter(clock, options.MaxAcceptsPerWindow, TimeSpan.FromMinutes(1));
    }

    /// <summary>Raised when the Bluetooth session commits remote clip bodies locally.</summary>
    public event Action<IReadOnlyList<RemoteClipApplied>>? RemoteClipsCommitted;

    /// <summary>
    /// Raised when the Bluetooth session changed 仅本机保留 marks in the store — bt1 carries
    /// no image bodies (ADR 0005 §4), so a live image announced here gets the downgraded
    /// marker and the open history list must refresh to show the annotation (§5).
    /// </summary>
    public event Action? LocalOnlyMarksChanged;

    /// <summary>Raised on a worker thread when the Bluetooth session authenticates or ends.</summary>
    public event Action? SessionsChanged;

    /// <summary>Raised when a device first crosses the failed bt1-handshake rate limit.</summary>
    public event Action<string>? DeviceLockedOut;

    public bool IsListening => acceptLoop is { IsCompleted: false };

    /// <summary>The device with an authenticated bt1 session right now, or null.</summary>
    public string? ConnectedDeviceId
    {
        get
        {
            lock (sessionGate)
            {
                return activeEngine is { IsReady: true } ? activeDeviceId : null;
            }
        }
    }

    /// <summary>
    /// Publishes the RFCOMM service and starts the accept loop. Throws when the radio or
    /// adapter is unavailable so the caller can surface "Bluetooth off" instead of a silent
    /// no-op; a fault after startup restarts the listener with <see cref="BluetoothSyncHostOptions.RestartDelay"/>.
    /// </summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (acceptLoop is not null)
        {
            throw new InvalidOperationException("The Bluetooth host is already running.");
        }

        await server.StartAsync(cancellationToken).ConfigureAwait(false);
        BluetoothLog.ListenerStarted(logger);
        loopCts = new CancellationTokenSource();
        acceptLoop = Task.Run(() => AcceptLoopAsync(loopCts.Token), CancellationToken.None);
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        var loop = acceptLoop;
        if (loop is null)
        {
            return;
        }

        acceptLoop = null;
        loopCts?.Cancel();
        lock (sessionGate)
        {
            activeEngine?.RequestClose();
        }

        await server.StopAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await loop.WaitAsync(TimeSpan.FromSeconds(10), cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            // The loop is stuck on platform teardown; disposal below abandons it.
        }

        loopCts?.Dispose();
        loopCts = null;
        BluetoothLog.ListenerStopped(logger);
    }

    private async Task AcceptLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            IRfcommConnection connection;
            try
            {
                connection = await server.AcceptAsync(token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception exception) when (exception is not OutOfMemoryException)
            {
                // The radio dropped or the platform listener faulted. Try to bring the
                // service back after a pause; StopAsync cancels the delay.
                BluetoothLog.ListenerFailed(logger, exception.GetType().Name);
                try
                {
                    await Task.Delay(options.RestartDelay, token).ConfigureAwait(false);
                    await server.StopAsync(token).ConfigureAwait(false);
                    await server.StartAsync(token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (Exception restartFailure) when (restartFailure is not OutOfMemoryException)
                {
                    BluetoothLog.ListenerFailed(logger, restartFailure.GetType().Name);
                }

                continue;
            }

            // One connection at a time by contract; a second phone gets the next accept
            // only after this session ends.
            await HandleConnectionAsync(connection, token).ConfigureAwait(false);
        }
    }

    private async Task HandleConnectionAsync(IRfcommConnection connection, CancellationToken token)
    {
        await using (connection.ConfigureAwait(false))
        {
            if (!acceptLimiter.TryAdmit(connection.RemoteAddress))
            {
                BluetoothLog.AcceptRateLimited(logger);
                return;
            }

            Bt1ListenerHandshakeOutcome outcome;
            using (var handshakeCts = CancellationTokenSource.CreateLinkedTokenSource(token))
            {
                handshakeCts.CancelAfter(options.HandshakeTimeout);
                try
                {
                    outcome = await Bt1ListenerHandshake.RunAsync(
                        connection.Stream,
                        Guid.Parse(store.LocalDeviceId),
                        LookupPairingAsync,
                        authThrottle,
                        handshakeCts.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!token.IsCancellationRequested)
                {
                    BluetoothLog.HandshakeAborted(logger, "HandshakeTimeout");
                    return;
                }
                catch (Exception exception) when (exception is IOException or ObjectDisposedException)
                {
                    BluetoothLog.HandshakeAborted(logger, exception.GetType().Name);
                    return;
                }
            }

            switch (outcome)
            {
                case Bt1ListenerHandshakeOutcome.Refused refused:
                    BluetoothLog.HandshakeRefused(logger, refused.ErrorCode, refused.Reason);
                    return;
                case Bt1ListenerHandshakeOutcome.PeerClosed:
                    return;
                case Bt1ListenerHandshakeOutcome.Established established:
                    await RunSessionAsync(connection, established, token).ConfigureAwait(false);
                    return;
                default:
                    throw new InvalidOperationException($"Unknown handshake outcome {outcome.GetType().Name}.");
            }
        }
    }

    private async Task RunSessionAsync(
        IRfcommConnection connection,
        Bt1ListenerHandshakeOutcome.Established channel,
        CancellationToken token)
    {
        var deviceId = channel.ClientDeviceId.ToString("D");
        BluetoothLog.SessionStarted(logger, deviceId);

        // ADR 0005 section 4: no image_clip_v2 over Bluetooth, so the inner session is
        // pinned to protocol v1 regardless of what the IP listener would offer.
        var sessionOptions = options.SessionOptions with { ProtocolVersion = ProtocolLimits.ProtocolVersion };
        var engine = new SyncSessionEngine(
            SyncSessionRole.Listener,
            store,
            secretProtector,
            sessionOptions,
            authThrottle,
            loggerFactory.CreateLogger("ClipSync.Peer.Bluetooth.Session"));
        engine.RemoteClipsCommitted += OnRemoteClipsCommitted;
        engine.LocalOnlyMarksChanged += OnLocalOnlyMarksChanged;
        engine.SessionReady += OnSessionReady;
        lock (sessionGate)
        {
            activeEngine = engine;
            activeDeviceId = deviceId;
        }

        try
        {
            await using var transport = new Bt1SyncTransport(connection.Stream, channel.Send, channel.Receive);
            var result = await engine.RunAsync(transport, token).ConfigureAwait(false);
            BluetoothLog.SessionEnded(logger, deviceId, result.ErrorCode ?? "clean");
        }
        catch (OperationCanceledException)
        {
            BluetoothLog.SessionEnded(logger, deviceId, "cancelled");
        }
        catch (Exception exception) when (exception is IOException or ObjectDisposedException)
        {
            BluetoothLog.SessionEnded(logger, deviceId, exception.GetType().Name);
        }
        finally
        {
            engine.RemoteClipsCommitted -= OnRemoteClipsCommitted;
            engine.LocalOnlyMarksChanged -= OnLocalOnlyMarksChanged;
            engine.SessionReady -= OnSessionReady;
            lock (sessionGate)
            {
                activeEngine = null;
                activeDeviceId = null;
            }

            engine.Dispose();
            SessionsChanged?.Invoke();
        }
    }

    /// <summary>
    /// Maps the claimed device id onto the same pairing store the IP listener trusts. The
    /// bt1 driver zeroes the returned secret; revocation and epoch checks happen there too.
    /// </summary>
    private async ValueTask<Bt1PairingRecord?> LookupPairingAsync(string deviceId, CancellationToken cancellationToken)
    {
        var device = await store.GetDeviceAsync(deviceId, cancellationToken).ConfigureAwait(false);
        if (device is null || string.IsNullOrEmpty(device.PairSecretProtected))
        {
            return null;
        }

        if (!Guid.TryParseExact(device.DeviceId, "D", out var parsedId))
        {
            return null;
        }

        byte[] secret;
        try
        {
            secret = secretProtector.Unprotect(Convert.FromBase64String(device.PairSecretProtected));
        }
        catch (FormatException)
        {
            return null;
        }
        catch (CryptographicException)
        {
            return null;
        }

        if (secret.Length != Bt1AuthProof.SecretLength)
        {
            CryptographicOperations.ZeroMemory(secret);
            return null;
        }

        return new Bt1PairingRecord(parsedId, device.TrustEpoch, device.IsRevoked, secret);
    }

    private void OnRemoteClipsCommitted(IReadOnlyList<RemoteClipApplied> batch) => RemoteClipsCommitted?.Invoke(batch);

    private void OnLocalOnlyMarksChanged() => LocalOnlyMarksChanged?.Invoke();

    private void OnSessionReady(string deviceId) => SessionsChanged?.Invoke();

    private void OnDeviceLockedOut(string deviceId) => DeviceLockedOut?.Invoke(deviceId);

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        authThrottle.DeviceLockedOut -= OnDeviceLockedOut;
        await server.DisposeAsync().ConfigureAwait(false);
    }
}
