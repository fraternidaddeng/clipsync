using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace ClipSync.Peer.Discovery;

/// <summary>
/// Broadcasts the discovery beacon from the plan: device ID, service port, and certificate
/// fingerprint only. No names, no clipboard data, no secrets. Android listens and offers
/// the device in its pairing UI; trust still requires the full pairing flow.
/// </summary>
public sealed class UdpDiscoveryBroadcaster(
    string deviceId,
    int servicePort,
    string certificateSha256,
    int discoveryPort = UdpDiscoveryBroadcaster.DefaultDiscoveryPort,
    IPEndPoint? targetOverride = null) : IDisposable
{
    public const int DefaultDiscoveryPort = 47653;

    private readonly UdpClient udp = CreateClient();

    public string BuildPayload() =>
        JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["v"] = 1,
            ["kind"] = "clipsync_discovery",
            ["device_id"] = deviceId,
            ["port"] = servicePort,
            ["cert_sha256"] = certificateSha256
        });

    /// <summary>Sends one beacon to every reachable IPv4 broadcast address (or the test override).</summary>
    public async Task BroadcastOnceAsync(CancellationToken cancellationToken = default)
    {
        var payload = Encoding.UTF8.GetBytes(BuildPayload());
        foreach (var target in ResolveTargets())
        {
            try
            {
                await udp.SendAsync(payload, target, cancellationToken).ConfigureAwait(false);
            }
            catch (SocketException)
            {
                // Interfaces come and go; a failed send on one target must not stop the rest.
            }
        }
    }

    private List<IPEndPoint> ResolveTargets()
    {
        if (targetOverride is not null)
        {
            return [targetOverride];
        }

        var targets = new List<IPEndPoint> { new(IPAddress.Broadcast, discoveryPort) };
        foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (networkInterface.OperationalStatus != OperationalStatus.Up
                || networkInterface.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            foreach (var unicast in networkInterface.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork || unicast.IPv4Mask is null)
                {
                    continue;
                }

                var addressBytes = unicast.Address.GetAddressBytes();
                var maskBytes = unicast.IPv4Mask.GetAddressBytes();
                var broadcastBytes = new byte[4];
                for (var index = 0; index < 4; index++)
                {
                    broadcastBytes[index] = (byte)(addressBytes[index] | ~maskBytes[index]);
                }

                targets.Add(new IPEndPoint(new IPAddress(broadcastBytes), discoveryPort));
            }
        }

        return targets;
    }

    private static UdpClient CreateClient()
    {
        var client = new UdpClient();
        client.EnableBroadcast = true;
        return client;
    }

    public void Dispose() => udp.Dispose();
}
