using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using ClipSync.App.Diagnostics;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Peer.Discovery;
using ClipSync.Peer.Pairing;
using ClipSync.Peer.Resilience;
using ClipSync.Peer.Security;
using ClipSync.Peer.Server;
using ClipSync.Peer.Sessions;

namespace ClipSync.App.Sync;

/// <summary>
/// Hosts the peer endpoint inside the WPF process: binds loopback plus private LAN addresses
/// (plus user-configured extras such as Tailscale IPs), and broadcasts the discovery beacon
/// on start, on network changes, and periodically. Recovers from suspend/resume and from
/// interface churn via <see cref="SyncResilienceController"/>: the beacon restarts, bind
/// addresses re-resolve, and the server rebinds when its addresses went stale.
/// </summary>
public sealed class PeerSyncHost : IAsyncDisposable
{
    public const int DefaultPort = 47654;

    private static readonly TimeSpan BeaconInterval = TimeSpan.FromMinutes(5);

    private readonly SqliteClipboardEventStore store;
    private readonly ISecretProtector secretProtector;
    private readonly X509Certificate2 certificate;
    private readonly PairingService? pairingService;
    private readonly ISystemStateEvents? systemEventsOverride;
    private readonly SyncResilienceOptions? resilienceOptions;
    private readonly Func<bool> outboundAllowed;
    private readonly Func<bool> imageSyncEnabled;
    private readonly Func<string?>? clipboardApplyState;
    private PeerServer? server;
    private UdpDiscoveryBroadcaster? broadcaster;
    private Timer? beaconTimer;
    private SyncResilienceController? resilience;
    private WindowsSystemStateEvents? ownedSystemEvents;
    private string? extraBindAddressSetting;
    private IReadOnlyList<IPAddress> boundAddresses = [];
    private bool started;

    public PeerSyncHost(
        SqliteClipboardEventStore store,
        ISecretProtector secretProtector,
        X509Certificate2 certificate,
        PairingService? pairingService = null,
        ISystemStateEvents? systemEvents = null,
        SyncResilienceOptions? resilienceOptions = null,
        Func<bool>? outboundAllowed = null,
        Func<bool>? imageSyncEnabled = null,
        Func<string?>? clipboardApplyState = null)
    {
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.secretProtector = secretProtector ?? throw new ArgumentNullException(nameof(secretProtector));
        this.certificate = certificate ?? throw new ArgumentNullException(nameof(certificate));
        this.pairingService = pairingService;
        systemEventsOverride = systemEvents;
        this.resilienceOptions = resilienceOptions;
        // Pause/private gate for outbound content in every session; re-read per drain tick
        // and per want_ranges pull so a toggle applies immediately (mirrors Android).
        this.outboundAllowed = outboundAllowed ?? (static () => true);
        // 图片同步 gate: while off, the listener refuses /v2 upgrades (dialers fall back to
        // v1) and live sessions accept/serve no image bodies; re-read so the toggle applies
        // immediately (strict audit §3: the setting must govern inbound, not only capture).
        // Fail-closed when unwired: image sync is opt-in on both platforms (ADR 0004), so a
        // caller that forgets the gate gets the same off-by-default as Android, not silent on.
        this.imageSyncEnabled = imageSyncEnabled ?? (static () => false);
        // Health-endpoint self-report of the local clipboard apply posture, so the paired
        // phone's 对端写入 segment can state facts. Null keeps the field off the wire.
        this.clipboardApplyState = clipboardApplyState;
        CertificateFingerprint = PeerCertificate.Fingerprint(certificate);
    }

    /// <summary>Raised on a worker thread when remote clip bodies commit locally.</summary>
    public event Action<IReadOnlyList<RemoteClipApplied>>? RemoteClipsCommitted;

    /// <summary>
    /// Raised on a worker thread when a session changed 仅本机保留 marks in the store
    /// (ADR 0005 §5) — the open history list must refresh to show or drop the annotation.
    /// </summary>
    public event Action? LocalOnlyMarksChanged;

    /// <summary>
    /// Raised on a worker thread when a peer session authenticates or ends.
    /// Read <see cref="ConnectedDeviceCount"/>/<see cref="ConnectedDeviceIds"/> for the new state.
    /// </summary>
    public event Action? SessionsChanged;

    /// <summary>
    /// Raised on a worker thread when a device first trips the failed-auth rate limit. The App
    /// surfaces it (diagnostics entry + tray notice); the payload is the claimed device id.
    /// </summary>
    public event Action<string>? DeviceLockedOut;

    /// <summary>
    /// Raised on a worker thread after a resume/network recovery changed the endpoint's
    /// externally visible state (online flag, port, or reachable hosts). The App pushes
    /// the fresh <see cref="IsRunning"/>/<see cref="Port"/>/<see cref="ConnectedDeviceCount"/>
    /// snapshot into the view model.
    /// </summary>
    public event Action? PeerStatusChanged;

    public string CertificateFingerprint { get; }

    public int Port => server?.Port ?? 0;

    public bool IsRunning => server is not null;

    /// <summary>Distinct paired devices with an authenticated session right now.</summary>
    public IReadOnlyList<string> ConnectedDeviceIds => server?.ConnectedDeviceIds ?? [];

    public int ConnectedDeviceCount => server?.ConnectedDeviceCount ?? 0;

    /// <summary>Claimed device ids rate-limited right now; empty when none.</summary>
    public IReadOnlyList<string> ThrottledDeviceIds => server?.ThrottledDeviceIds ?? [];

    public PairingService? Pairing => pairingService;

    /// <summary>
    /// Host candidates a scanning phone can actually reach: the non-loopback bind addresses,
    /// refreshed on resume and network changes. The QR payload allows at most eight entries.
    /// </summary>
    public IReadOnlyList<string> ReachableHosts { get; private set; } = [];

    public async Task StartAsync(string? extraBindAddresses, CancellationToken cancellationToken = default)
    {
        if (server is not null)
        {
            return;
        }

        extraBindAddressSetting = extraBindAddresses;
        var addresses = ResolveBindAddresses(extraBindAddresses);
        server = await StartServerAsync(addresses, DefaultPort, cancellationToken).ConfigureAwait(false);
        boundAddresses = addresses;
        ReachableHosts = addresses
            .Where(address => !IPAddress.IsLoopback(address))
            .Select(address => address.ToString())
            .Take(NetworkRefreshPlanner.MaxReachableHosts)
            .ToList();

        broadcaster = new UdpDiscoveryBroadcaster(store.LocalDeviceId, server.Port, CertificateFingerprint);
        await BroadcastQuietlyAsync().ConfigureAwait(false);
        beaconTimer = new Timer(_ => _ = BroadcastQuietlyAsync(), null, BeaconInterval, BeaconInterval);

        var systemEvents = systemEventsOverride;
        if (systemEvents is null)
        {
            ownedSystemEvents = new WindowsSystemStateEvents();
            systemEvents = ownedSystemEvents;
        }

        resilience = new SyncResilienceController(
            systemEvents,
            onResume: token => RecoverAsync(afterResume: true, token),
            onNetworkChanged: token => RecoverAsync(afterResume: false, token),
            resilienceOptions,
            onSuspend: EnterSuspend);
        started = true;
        LocalDiagnostics.Write($"peer_server_started_port_{server.Port}");
    }

    /// <summary>
    /// Suspend path, synchronous inside the OS pre-sleep window: refuse new sessions first
    /// so a fast peer redial cannot open a half-alive connection right before sleep, then
    /// close the live ones. The resume recovery pass reopens the gate.
    /// </summary>
    internal void EnterSuspend()
    {
        var current = server;
        if (current is null)
        {
            return;
        }

        current.SetRefuseNewSessions(true);
        current.DisconnectAllSessions();
        LocalDiagnostics.Write("peer_suspend_sessions_gated");
    }

    public void DisconnectDevice(string deviceId) => server?.DisconnectDevice(deviceId);

    /// <summary>
    /// Closes every authenticated session so peers redial and renegotiate the wire version.
    /// Used when the 图片同步 gate flips: the version (v2 image frames vs text-only v1) is
    /// fixed at dial time, so without this bounce a live v1 session keeps the freshly enabled
    /// toggle inert until the next incidental disconnect — which a stable network may never
    /// produce. The phone's reconnect loop redials within about a second.
    /// </summary>
    public void DisconnectAllSessions() => server?.DisconnectAllSessions();

    /// <summary>
    /// One recovery pass, serialized by <see cref="SyncResilienceController"/>. Re-resolves
    /// bind addresses, rebinds the server when its bindings went stale (always after resume,
    /// since suspend killed every session anyway), refreshes <see cref="ReachableHosts"/>,
    /// restarts the discovery broadcaster and beacon timer, and sends one beacon immediately.
    /// </summary>
    internal async Task RecoverAsync(bool afterResume, CancellationToken cancellationToken = default)
    {
        if (!started)
        {
            return;
        }

        if (afterResume)
        {
            // Reopen the suspend gate before anything else; even when the rebind below
            // fails, peers must be able to redial the surviving listener.
            server?.SetRefuseNewSessions(false);
        }

        var resolved = ResolveBindAddresses(extraBindAddressSetting);
        var plan = NetworkRefreshPlanner.Plan(new NetworkRefreshContext
        {
            BoundAddresses = server is null ? [] : boundAddresses,
            ResolvedAddresses = resolved,
            CurrentReachableHosts = ReachableHosts,
            AfterResume = afterResume,
            ServerListening = server is not null
        });

        if (plan.RestartServer)
        {
            await RestartServerAsync(resolved, cancellationToken).ConfigureAwait(false);
        }

        // A failed rebind leaves no listener: advertise nothing until a later pass succeeds.
        ReachableHosts = server is null ? [] : plan.ReachableHosts;

        if (plan.RestartBroadcaster)
        {
            broadcaster?.Dispose();
            broadcaster = server is null
                ? null
                : new UdpDiscoveryBroadcaster(store.LocalDeviceId, server.Port, CertificateFingerprint);
        }

        if (afterResume)
        {
            // The periodic timer's due time drifts across suspend; re-anchor the cadence.
            beaconTimer?.Change(BeaconInterval, BeaconInterval);
        }

        await BroadcastQuietlyAsync().ConfigureAwait(false);

        if (afterResume)
        {
            LocalDiagnostics.Write($"peer_resume_recovered_port_{Port}");
        }
        else if (plan.HostsChanged || plan.RestartServer)
        {
            LocalDiagnostics.Write($"peer_network_refreshed_hosts_{ReachableHosts.Count}");
        }

        if (afterResume || plan.HostsChanged || plan.RestartServer)
        {
            PeerStatusChanged?.Invoke();
        }
    }

    /// <summary>
    /// Tears down the current listener (its sessions are already dead or its bindings are
    /// gone) and rebinds on the fresh address set, keeping the old port when still free so
    /// printed/scanned QR payloads stay valid. Failure leaves the endpoint offline; the
    /// next resume or network-change pass retries.
    /// </summary>
    private async Task RestartServerAsync(List<IPAddress> addresses, CancellationToken cancellationToken)
    {
        var preferredPort = server?.Port ?? DefaultPort;
        if (server is not null)
        {
            var old = server;
            server = null;
            old.RemoteClipsCommitted -= OnRemoteClipsCommitted;
            old.LocalOnlyMarksChanged -= OnLocalOnlyMarksChanged;
            old.SessionsChanged -= OnSessionsChanged;
            old.DeviceLockedOut -= OnDeviceLockedOut;
            await old.DisposeAsync().ConfigureAwait(false);
        }

        try
        {
            server = await StartServerAsync(addresses, preferredPort, cancellationToken).ConfigureAwait(false);
            boundAddresses = addresses;
            LocalDiagnostics.Write($"peer_server_rebound_port_{server.Port}");
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            boundAddresses = [];
            LocalDiagnostics.Write($"peer_rebind_failed_{exception.GetType().Name}");
        }
    }

    /// <summary>Starts a listener on the preferred port, falling back to an ephemeral one.</summary>
    private async Task<PeerServer> StartServerAsync(
        List<IPAddress> addresses,
        int preferredPort,
        CancellationToken cancellationToken)
    {
        var sessionOptions = new SyncSessionOptions
        {
            ClientVersion = typeof(PeerSyncHost).Assembly.GetName().Version?.ToString(3) ?? "0.2.0",
            Platform = "windows",
            OutboundAllowed = outboundAllowed,
            ImageSyncEnabled = imageSyncEnabled
        };

        var candidate = new PeerServer(store, secretProtector, new PeerServerOptions
        {
            Certificate = certificate,
            SessionOptions = sessionOptions,
            BindAddresses = addresses,
            Port = preferredPort,
            ClipboardApplyState = clipboardApplyState
        }, pairingService: pairingService);
        try
        {
            await candidate.StartAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (IOException)
        {
            // The preferred port is taken; fall back to an ephemeral one. Discovery and the
            // health endpoint both advertise the actual port, so peers still find us.
            await candidate.DisposeAsync().ConfigureAwait(false);
            candidate = new PeerServer(store, secretProtector, new PeerServerOptions
            {
                Certificate = certificate,
                SessionOptions = sessionOptions,
                BindAddresses = addresses,
                Port = 0,
                ClipboardApplyState = clipboardApplyState
            }, pairingService: pairingService);
            await candidate.StartAsync(cancellationToken).ConfigureAwait(false);
        }

        candidate.RemoteClipsCommitted += OnRemoteClipsCommitted;
        candidate.LocalOnlyMarksChanged += OnLocalOnlyMarksChanged;
        candidate.SessionsChanged += OnSessionsChanged;
        candidate.DeviceLockedOut += OnDeviceLockedOut;
        return candidate;
    }

    private void OnRemoteClipsCommitted(IReadOnlyList<RemoteClipApplied> batch) =>
        RemoteClipsCommitted?.Invoke(batch);

    private void OnLocalOnlyMarksChanged() => LocalOnlyMarksChanged?.Invoke();

    private void OnSessionsChanged() => SessionsChanged?.Invoke();

    private void OnDeviceLockedOut(string deviceId) => DeviceLockedOut?.Invoke(deviceId);

    private async Task BroadcastQuietlyAsync()
    {
        try
        {
            if (broadcaster is not null)
            {
                await broadcaster.BroadcastOnceAsync().ConfigureAwait(false);
            }
        }
        catch (SocketException)
        {
            // No usable interface right now; the periodic timer will try again.
        }
        catch (ObjectDisposedException)
        {
        }
    }

    /// <summary>Loopback and private LAN IPv4 addresses by default, plus explicit user extras.</summary>
    internal static List<IPAddress> ResolveBindAddresses(string? extraBindAddresses)
    {
        var addresses = new List<IPAddress> { IPAddress.Loopback };
        foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (networkInterface.OperationalStatus != OperationalStatus.Up
                || networkInterface.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            foreach (var unicast in networkInterface.GetIPProperties().UnicastAddresses)
            {
                var address = unicast.Address;
                if (address.AddressFamily == AddressFamily.InterNetwork
                    && IsPrivateIpv4(address)
                    && !addresses.Contains(address))
                {
                    addresses.Add(address);
                }
            }
        }

        if (!string.IsNullOrWhiteSpace(extraBindAddresses))
        {
            var extras = extraBindAddresses.Split(
                [',', ';', ' ', '\r', '\n'],
                StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            foreach (var extra in extras)
            {
                if (IPAddress.TryParse(extra, out var parsed) && !addresses.Contains(parsed))
                {
                    addresses.Add(parsed);
                }
            }
        }

        return addresses;
    }

    private static bool IsPrivateIpv4(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes[0] switch
        {
            10 => true,
            172 => bytes[1] >= 16 && bytes[1] <= 31,
            192 => bytes[1] == 168,
            _ => false
        };
    }

    public async ValueTask DisposeAsync()
    {
        started = false;

        // The controller drains any in-flight recovery before the teardown below, so a
        // recovery pass never races the disposal of the server or broadcaster.
        if (resilience is not null)
        {
            await resilience.DisposeAsync().ConfigureAwait(false);
            resilience = null;
        }

        ownedSystemEvents?.Dispose();
        ownedSystemEvents = null;

        if (beaconTimer is not null)
        {
            await beaconTimer.DisposeAsync().ConfigureAwait(false);
            beaconTimer = null;
        }

        broadcaster?.Dispose();
        broadcaster = null;

        if (server is not null)
        {
            server.RemoteClipsCommitted -= OnRemoteClipsCommitted;
            server.LocalOnlyMarksChanged -= OnLocalOnlyMarksChanged;
            server.SessionsChanged -= OnSessionsChanged;
            server.DeviceLockedOut -= OnDeviceLockedOut;
            await server.DisposeAsync().ConfigureAwait(false);
            server = null;
        }
    }
}
