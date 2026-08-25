using System.Security.Cryptography;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Sessions;

namespace ClipSync.Peer.Bluetooth;

/// <summary>
/// One stored pairing as the bt1 listener needs it: identity, revocation, current trust
/// epoch, and the already-unprotected 32-byte pair secret. The caller owns the secret
/// buffer and zeroes it after the handshake.
/// </summary>
public sealed record Bt1PairingRecord(Guid DeviceId, long TrustEpoch, bool IsRevoked, byte[] PairSecret);

/// <summary>Resolves the claimed client device id to a stored pairing; null when unknown or the secret is unavailable.</summary>
public delegate ValueTask<Bt1PairingRecord?> Bt1PairingLookup(string deviceId, CancellationToken cancellationToken);

/// <summary>Result of one listener-side bt1 handshake attempt.</summary>
public abstract record Bt1ListenerHandshakeOutcome
{
    private Bt1ListenerHandshakeOutcome()
    {
    }

    /// <summary>
    /// Both proofs verified; the channel is up. <see cref="Send"/> encrypts listener-to-client,
    /// <see cref="Receive"/> decrypts client-to-listener. The caller owns disposal.
    /// </summary>
    public sealed record Established(Bt1FrameEncryptor Send, Bt1FrameDecryptor Receive, Guid ClientDeviceId) : Bt1ListenerHandshakeOutcome;

    /// <summary>
    /// The handshake was refused — locally (a best-effort bt1_error was already sent) or by
    /// the client's own bt1_error. <see cref="Reason"/> is a diagnostic label, never wire text.
    /// </summary>
    public sealed record Refused(string ErrorCode, string Reason) : Bt1ListenerHandshakeOutcome;

    /// <summary>The peer closed the stream mid-handshake; nothing to answer.</summary>
    public sealed record PeerClosed : Bt1ListenerHandshakeOutcome;
}

/// <summary>
/// The accept side of the bt1 handshake (docs/protocol-bt1.md section 3): client hello,
/// listener hello, client auth, listener auth — no business byte in either direction until
/// both proofs verified. Pure stream logic so unit tests drive it over in-memory pipes;
/// the RFCOMM host supplies socket streams and owns the 30-second abort via the token.
/// Never logs nonces, proofs, or secrets.
/// </summary>
public static class Bt1ListenerHandshake
{
    public static async Task<Bt1ListenerHandshakeOutcome> RunAsync(
        Stream stream,
        Guid localDeviceId,
        Bt1PairingLookup lookupPairing,
        IAuthFailureSink? authFailures,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(lookupPairing);

        Bt1PairingRecord? pairing = null;
        try
        {
            // ---- bt1_client_hello ----
            var helloRead = await ReadMessageAsync(stream, cancellationToken).ConfigureAwait(false);
            if (helloRead is ReadOutcome.Eof)
            {
                return new Bt1ListenerHandshakeOutcome.PeerClosed();
            }

            if (helloRead is ReadOutcome.Violation helloViolation)
            {
                return await RefuseAsync(stream, helloViolation.ErrorCode, helloViolation.Reason, cancellationToken)
                    .ConfigureAwait(false);
            }

            var helloMessage = (ReadOutcome.Message)helloRead;

            if (helloMessage.Value is Bt1HandshakeMessage.ChannelError clientRefusal)
            {
                return new Bt1ListenerHandshakeOutcome.Refused(clientRefusal.Code, "client refused the handshake");
            }

            if (helloMessage.Value is not Bt1HandshakeMessage.Hello { SenderRole: Bt1Role.Client } hello)
            {
                return await RefuseAsync(stream, Bt1ErrorCodes.SchemaViolation, "expected bt1_client_hello", cancellationToken)
                    .ConfigureAwait(false);
            }

            var claimedDeviceId = hello.DeviceId.ToString("D");
            if (hello.DeviceId == localDeviceId)
            {
                return await RefuseAsync(stream, Bt1ErrorCodes.AuthFailed, "self_connection", cancellationToken)
                    .ConfigureAwait(false);
            }

            pairing = await lookupPairing(claimedDeviceId, cancellationToken).ConfigureAwait(false);
            if (pairing is null)
            {
                // Unknown claims count against the throttle exactly like the IP listener's
                // unknown_device path, so a guessing radio locks itself out.
                authFailures?.RecordAuthFailure(claimedDeviceId);
                return await RefuseAsync(stream, Bt1ErrorCodes.AuthFailed, "unknown_device", cancellationToken)
                    .ConfigureAwait(false);
            }

            if (pairing.IsRevoked)
            {
                return await RefuseAsync(stream, Bt1ErrorCodes.AuthFailed, "device_revoked", cancellationToken)
                    .ConfigureAwait(false);
            }

            if (authFailures?.IsThrottled(claimedDeviceId) == true)
            {
                return await RefuseAsync(stream, Bt1ErrorCodes.RateLimited, "auth_throttled", cancellationToken)
                    .ConfigureAwait(false);
            }

            if (hello.TrustEpoch != pairing.TrustEpoch)
            {
                return await RefuseAsync(stream, Bt1ErrorCodes.AuthFailed, "trust_epoch_mismatch", cancellationToken)
                    .ConfigureAwait(false);
            }

            if (pairing.PairSecret.Length != Bt1AuthProof.SecretLength)
            {
                return await RefuseAsync(stream, Bt1ErrorCodes.AuthFailed, "secret_unavailable", cancellationToken)
                    .ConfigureAwait(false);
            }

            // ---- bt1_listener_hello ----
            var nonceListener = RandomNumberGenerator.GetBytes(Bt1AuthProof.NonceLength);
            var nonceClient = hello.Nonce.ToArray();
            await Bt1StreamFrames.WriteHandshakeFrameAsync(
                stream,
                Bt1HandshakeCodec.SerializeHello(Bt1Role.Listener, localDeviceId, pairing.TrustEpoch, nonceListener),
                cancellationToken).ConfigureAwait(false);

            // ---- bt1_client_auth ----
            var authRead = await ReadMessageAsync(stream, cancellationToken).ConfigureAwait(false);
            if (authRead is ReadOutcome.Eof)
            {
                return new Bt1ListenerHandshakeOutcome.PeerClosed();
            }

            if (authRead is ReadOutcome.Violation authViolation)
            {
                return await RefuseAsync(stream, authViolation.ErrorCode, authViolation.Reason, cancellationToken)
                    .ConfigureAwait(false);
            }

            var authMessage = (ReadOutcome.Message)authRead;

            if (authMessage.Value is Bt1HandshakeMessage.ChannelError clientAuthRefusal)
            {
                return new Bt1ListenerHandshakeOutcome.Refused(clientAuthRefusal.Code, "client refused the handshake");
            }

            if (authMessage.Value is not Bt1HandshakeMessage.Auth { SenderRole: Bt1Role.Client } clientAuth)
            {
                return await RefuseAsync(stream, Bt1ErrorCodes.SchemaViolation, "expected bt1_client_auth", cancellationToken)
                    .ConfigureAwait(false);
            }

            var proofValid = Bt1AuthProof.Verify(
                pairing.PairSecret,
                Bt1Role.Client,
                nonceClient,
                nonceListener,
                clientDeviceId: hello.DeviceId,
                listenerDeviceId: localDeviceId,
                trustEpoch: pairing.TrustEpoch,
                proof: clientAuth.Proof.Span);
            if (!proofValid)
            {
                // The client proves first (section 3): an invalid proof means the listener
                // must never send its own, so the pair secret is not confirmed to a stranger.
                authFailures?.RecordAuthFailure(claimedDeviceId);
                return await RefuseAsync(stream, Bt1ErrorCodes.AuthFailed, "client_proof_invalid", cancellationToken)
                    .ConfigureAwait(false);
            }

            // ---- bt1_listener_auth ----
            var listenerProof = Bt1AuthProof.Compute(
                pairing.PairSecret,
                Bt1Role.Listener,
                nonceClient,
                nonceListener,
                clientDeviceId: hello.DeviceId,
                listenerDeviceId: localDeviceId,
                trustEpoch: pairing.TrustEpoch);
            await Bt1StreamFrames.WriteHandshakeFrameAsync(
                stream,
                Bt1HandshakeCodec.SerializeAuth(Bt1Role.Listener, listenerProof),
                cancellationToken).ConfigureAwait(false);

            var keys = Bt1KeySchedule.Derive(pairing.PairSecret, nonceClient, nonceListener);
            return new Bt1ListenerHandshakeOutcome.Established(
                Send: new Bt1FrameEncryptor(keys.ListenerToClient.Span),
                Receive: new Bt1FrameDecryptor(keys.ClientToListener.Span),
                ClientDeviceId: hello.DeviceId);
        }
        finally
        {
            if (pairing is not null)
            {
                CryptographicOperations.ZeroMemory(pairing.PairSecret);
            }
        }
    }

    private abstract record ReadOutcome
    {
        private ReadOutcome()
        {
        }

        public sealed record Message(Bt1HandshakeMessage Value) : ReadOutcome;

        public sealed record Violation(string ErrorCode, string Reason) : ReadOutcome;

        public sealed record Eof : ReadOutcome;
    }

    /// <summary>Reads and parses the next handshake message, folding framing and codec violations together.</summary>
    private static async ValueTask<ReadOutcome> ReadMessageAsync(Stream stream, CancellationToken cancellationToken)
    {
        string? payload;
        try
        {
            payload = await Bt1StreamFrames.ReadHandshakePayloadAsync(stream, cancellationToken).ConfigureAwait(false);
        }
        catch (Bt1FramingException framing)
        {
            return new ReadOutcome.Violation(framing.ErrorCode, framing.Message);
        }

        if (payload is null)
        {
            return new ReadOutcome.Eof();
        }

        return Bt1HandshakeCodec.Parse(payload) switch
        {
            Bt1HandshakeParseOutcome.Success success => new ReadOutcome.Message(success.Message),
            Bt1HandshakeParseOutcome.Failure failure => new ReadOutcome.Violation(failure.ErrorCode, failure.Reason),
            var other => throw new InvalidOperationException($"Unknown parse outcome {other.GetType().Name}.")
        };
    }

    /// <summary>Sends a best-effort bt1_error and returns the refusal; the caller closes the stream.</summary>
    private static async ValueTask<Bt1ListenerHandshakeOutcome> RefuseAsync(
        Stream stream,
        string errorCode,
        string reason,
        CancellationToken cancellationToken)
    {
        var wireCode = Bt1ErrorCodes.WireCodes.Contains(errorCode) ? errorCode : Bt1ErrorCodes.SchemaViolation;
        try
        {
            await Bt1StreamFrames.WriteHandshakeFrameAsync(
                stream,
                Bt1HandshakeCodec.SerializeError(wireCode),
                cancellationToken).ConfigureAwait(false);
        }
        catch (IOException)
        {
            // The peer is already gone; the refusal outcome still stands.
        }
        catch (ObjectDisposedException)
        {
        }

        return new Bt1ListenerHandshakeOutcome.Refused(errorCode, reason);
    }
}
