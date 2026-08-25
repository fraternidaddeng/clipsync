using System.Globalization;
using System.Net.WebSockets;
using System.Security.Cryptography;
using ClipSync.Peer.Transport;

namespace ClipSync.Peer.Client;

/// <summary>
/// Dial side of the peer connection: connects with the protocol version header and accepts
/// exactly the pinned certificate fingerprint, nothing else. Chain and hostname are ignored
/// by design; the pin from pairing is the whole trust decision.
/// </summary>
public static class PeerSyncClient
{
    public static Task<ISyncTransport> ConnectAsync(
        string host,
        int port,
        string expectedCertificateSha256,
        CancellationToken cancellationToken) =>
        ConnectAsync(host, port, expectedCertificateSha256, protocolVersion: 1, cancellationToken);

    public static async Task<ISyncTransport> ConnectAsync(
        string host,
        int port,
        string expectedCertificateSha256,
        int protocolVersion,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(host);
        ArgumentException.ThrowIfNullOrWhiteSpace(expectedCertificateSha256);
        if (protocolVersion is not (1 or 2))
        {
            throw new ArgumentOutOfRangeException(nameof(protocolVersion));
        }

        var socket = new ClientWebSocket();
        try
        {
            // Peers are paired private LAN or Tailscale addresses, never public hostnames,
            // so the system proxy (WinINet settings, or Clash/Surge in system-proxy mode;
            // also HTTPS_PROXY/ALL_PROXY environment variables) must never sit on this
            // path: the proxy either cannot reach an address that is only routable from
            // this machine, or becomes a man-in-the-middle that the certificate pin then
            // rejects. ClientWebSocket defaults to the system proxy; null means "connect
            // directly", keeping sync working even when a global proxy is switched on.
            socket.Options.Proxy = null;
            socket.Options.SetRequestHeader("X-Protocol-Version", protocolVersion.ToString(CultureInfo.InvariantCulture));
            socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
            {
                if (certificate is null)
                {
                    return false;
                }

                var fingerprint = Convert.ToHexString(SHA256.HashData(certificate.GetRawCertData())).ToLowerInvariant();
                return string.Equals(fingerprint, expectedCertificateSha256, StringComparison.Ordinal);
            };

            var path = protocolVersion == 2 ? "/v2/peer/sync" : "/v1/peer/sync";
            var uri = new Uri($"wss://{host}:{port}{path}");
            await socket.ConnectAsync(uri, cancellationToken).ConfigureAwait(false);
            return new WebSocketSyncTransport(socket);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }
}
