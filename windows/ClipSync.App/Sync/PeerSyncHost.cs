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
using ClipSync.Peer.Security;
using ClipSync.Peer.Server;
using ClipSync.Peer.Sessions;

namespace ClipSync.App.Sync;

/// <summary>
/// Hosts the peer endpoint inside the WPF process: binds loopback plus private LAN addresses
/// (plus user-configured extras such as Tailscale IPs), and broadcasts the discovery beacon
/// on start, on network changes, and periodically.
/// </summary>
public sealed class PeerSyncHost : IAsyncDisposable
{
    public const int DefaultPort = 47654;

    private static readonly TimeSpan BeaconInterval = TimeSpan.FromMinutes(5);

    private readonly SqliteClipboardEventStore store;
    private readonly ISecretProtector secretProtector;
    private readonly X509Certificate2 certificate;
    private readonly PairingService? pairingService;
    private PeerServer? server;
    private UdpDiscoveryBroadcaster? broadcaster;
    private Timer? beaconTimer;
    private NetworkAddressChangedEventHandler? networkChangedHandler;

    public PeerSyncHost(
        SqliteClipboardEventStore store,
        ISecretProtector secretProtector,
        X509Certificate2 certificate,
        PairingService? pairingService = null)
    {
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.secretProtector = secretProtector ?? throw new ArgumentNullException(nameof(secretProtector));
        this.certificate = certificate ?? throw new ArgumentNullException(nameof(certificate));
        this.pairingService = pairingService;
        CertificateFingerprint = PeerCertificate.Fingerprint(certificate);
    }

    /// <summary>Raised on a worker thread when remote clip bodies commit locally.</summary>
    public event Action<IReadOnlyList<RemoteClipApplied>>? RemoteClipsCommitted;

    public string CertificateFingerprint { get; }

    public int Port => server?.Port ?? 0;

    public bool IsRunning => server is not null;

    public PairingService? Pairing => pairingService;

    /// <summary>
    /// Host candidates a scanning phone can actually reach: the non-loopback bind addresses,
    /// captured at start. The QR payload allows at most eight entries.
    /// </summary>
    public IReadOnlyList<string> ReachableHosts { get; private set; } = [];

    public async Task StartAsync(string? extraBindAddresses, CancellationToken cancellationToken = default)
    {
        if (server is not null)
        {
            return;
        }

        var sessionOptions = new SyncSessionOptions
        {
            ClientVersion = typeof(PeerSyncHost).Assembly.GetName().Version?.ToString(3) ?? "0.2.0",
            Platform = "windows"
        };

        var addresses = ResolveBindAddresses(extraBindAddresses);
        ReachableHosts = addresses
            .Where(address => !IPAddress.IsLoopback(address))
            .Select(address => address.ToString())
            .Take(8)
            .ToList();
        var candidate = new PeerServer(store, secretProtector, new PeerServerOptions
        {
            Certificate = certificate,
            SessionOptions = sessionOptions,
            BindAddresses = addresses,
            Port = DefaultPort
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
                Port = 0
            }, pairingService: pairingService);
            await candidate.StartAsync(cancellationToken).ConfigureAwait(false);
        }

        candidate.RemoteClipsCommitted += OnRemoteClipsCommitted;
        server = candidate;

        broadcaster = new UdpDiscoveryBroadcaster(store.LocalDeviceId, server.Port, CertificateFingerprint);
        await BroadcastQuietlyAsync().ConfigureAwait(false);
        beaconTimer = new Timer(_ => _ = BroadcastQuietlyAsync(), null, BeaconInterval, BeaconInterval);
        networkChangedHandler = (_, _) => _ = BroadcastQuietlyAsync();
        NetworkChange.NetworkAddressChanged += networkChangedHandler;
        LocalDiagnostics.Write($"peer_server_started_port_{server.Port}");
    }

    public void DisconnectDevice(string deviceId) => server?.DisconnectDevice(deviceId);

    /// <summary>Closes every live peer session without stopping the listener (e.g. before sleep).</summary>
    public void DisconnectAllSessions() => server?.DisconnectAllSessions();

    /// <summary>
    /// Re-broadcasts the discovery beacon so peers redial after wake. Same path as the
    /// periodic timer and <see cref="NetworkChange.NetworkAddressChanged"/>.
    /// </summary>
    public void NudgeReconnect() => _ = BroadcastQuietlyAsync();

    private void OnRemoteClipsCommitted(IReadOnlyList<RemoteClipApplied> batch) =>
        RemoteClipsCommitted?.Invoke(batch);

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
        if (networkChangedHandler is not null)
        {
            NetworkChange.NetworkAddressChanged -= networkChangedHandler;
            networkChangedHandler = null;
        }

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
            await server.DisposeAsync().ConfigureAwait(false);
            server = null;
        }
    }
}
