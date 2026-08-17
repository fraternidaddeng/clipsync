using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ClipSync.Peer.Transport;

namespace ClipSync.Peer.Client;

/// <summary>
/// Dial side of the peer connection: connects with the protocol version header and accepts
/// exactly the pinned certificate fingerprint, nothing else. Chain and hostname are ignored
/// by design; the pin from pairing is the whole trust decision.
/// </summary>
public static class PeerSyncClient
{
    public static async Task<ISyncTransport> ConnectAsync(
        string host,
        int port,
        string expectedCertificateSha256,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(host);
        ArgumentException.ThrowIfNullOrWhiteSpace(expectedCertificateSha256);

        var socket = new ClientWebSocket();
        try
        {
            socket.Options.SetRequestHeader("X-Protocol-Version", "1");
            socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
            {
                if (certificate is null)
                {
                    return false;
                }

                var fingerprint = Convert.ToHexString(SHA256.HashData(certificate.GetRawCertData())).ToLowerInvariant();
                return string.Equals(fingerprint, expectedCertificateSha256, StringComparison.Ordinal);
            };

            var uri = new Uri($"wss://{host}:{port}/v1/peer/sync");
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
