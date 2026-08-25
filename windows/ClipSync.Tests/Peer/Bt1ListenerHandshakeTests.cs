using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Bluetooth;
using ClipSync.Peer.Server;

namespace ClipSync.Tests.Peer;

public sealed class Bt1ListenerHandshakeTests
{
    private static readonly Guid ListenerId = Guid.Parse("11111111-1111-4111-8111-111111111111");
    private static readonly Guid ClientId = Guid.Parse("22222222-2222-4222-8222-222222222222");
    private const long Epoch = 3;

    private static Bt1PairingLookup LookupReturning(Bt1PairingRecord? record) =>
        (_, _) => ValueTask.FromResult(record is null
            ? null
            // The driver zeroes the returned secret after each attempt, so every lookup
            // hands out a fresh copy exactly like a store-backed implementation would.
            : record with { PairSecret = (byte[])record.PairSecret.Clone() });

    private static Bt1PairingRecord Pairing(byte[] secret, bool revoked = false, long epoch = Epoch) =>
        new(ClientId, epoch, revoked, secret);

    private static Task<Bt1ListenerHandshakeOutcome> RunListenerAsync(
        Stream listenerSide,
        Bt1PairingLookup lookup,
        AuthThrottle? throttle = null)
        => Bt1ListenerHandshake.RunAsync(listenerSide, ListenerId, lookup, throttle, CancellationToken.None);

    [Fact]
    public async Task SuccessfulHandshakeYieldsChannelsThatInteroperateEndToEnd()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret)));
            var (clientSend, clientReceive) = await Bt1TestClient.HandshakeAsync(
                clientSide, secret, ClientId, ListenerId, Epoch);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Established>(await listenerTask);
            Assert.Equal(ClientId, outcome.ClientDeviceId);

            // Client -> listener and listener -> client through independently derived keys.
            var toListener = clientSend.EncryptFrame(Encoding.UTF8.GetBytes("手机复制的一段文本"));
            Assert.True(outcome.Receive.TryDecryptPayload(
                toListener.AsSpan(Bt1Frames.LengthPrefixLength), out var listenerSaw));
            Assert.Equal("手机复制的一段文本", Encoding.UTF8.GetString(listenerSaw));

            var toClient = outcome.Send.EncryptFrame(Encoding.UTF8.GetBytes("ack from windows"));
            Assert.True(clientReceive.TryDecryptPayload(
                toClient.AsSpan(Bt1Frames.LengthPrefixLength), out var clientSaw));
            Assert.Equal("ack from windows", Encoding.UTF8.GetString(clientSaw));
        }
    }

    [Fact]
    public async Task UnknownDeviceIsRefusedAndCountedAgainstTheThrottle()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var throttle = new AuthThrottle(TimeProvider.System, maxFailures: 1);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(null), throttle);
            var refusal = await Assert.ThrowsAsync<Bt1TestRefusalException>(
                () => Bt1TestClient.HandshakeAsync(clientSide, secret, ClientId, ListenerId, Epoch));
            Assert.Equal(Bt1ErrorCodes.AuthFailed, refusal.Code);

            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal(Bt1ErrorCodes.AuthFailed, outcome.ErrorCode);
            Assert.Equal("unknown_device", outcome.Reason);
            Assert.True(throttle.IsThrottled(ClientId.ToString("D")));
        }
    }

    [Fact]
    public async Task RevokedPairingIsRefusedWithAuthFailed()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret, revoked: true)));
            var refusal = await Assert.ThrowsAsync<Bt1TestRefusalException>(
                () => Bt1TestClient.HandshakeAsync(clientSide, secret, ClientId, ListenerId, Epoch));
            Assert.Equal(Bt1ErrorCodes.AuthFailed, refusal.Code);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal("device_revoked", outcome.Reason);
        }
    }

    [Fact]
    public async Task TrustEpochMismatchIsRefusedBeforeAnyProofIsSent()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            // The stored pairing moved to epoch 4 (re-pair); the client still claims 3.
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret, epoch: Epoch + 1)));
            var refusal = await Assert.ThrowsAsync<Bt1TestRefusalException>(
                () => Bt1TestClient.HandshakeAsync(clientSide, secret, ClientId, ListenerId, Epoch));
            Assert.Equal(Bt1ErrorCodes.AuthFailed, refusal.Code);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal("trust_epoch_mismatch", outcome.Reason);
        }
    }

    [Fact]
    public async Task WrongClientProofIsRefusedAndTheListenerNeverProves()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var wrongSecret = RandomNumberGenerator.GetBytes(32);
        var throttle = new AuthThrottle(TimeProvider.System, maxFailures: 5);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret)), throttle);
            // The client authenticates with the wrong secret: its proof cannot verify, and
            // the message it receives back must be bt1_error, never bt1_listener_auth.
            var refusal = await Assert.ThrowsAsync<Bt1TestRefusalException>(
                () => Bt1TestClient.HandshakeAsync(clientSide, wrongSecret, ClientId, ListenerId, Epoch));
            Assert.Equal(Bt1ErrorCodes.AuthFailed, refusal.Code);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal("client_proof_invalid", outcome.Reason);
        }
    }

    [Fact]
    public async Task ThrottledDeviceIsRateLimitedWithoutReachingProofVerification()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var throttle = new AuthThrottle(TimeProvider.System, maxFailures: 1);
        throttle.RecordAuthFailure(ClientId.ToString("D"));
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret)), throttle);
            var refusal = await Assert.ThrowsAsync<Bt1TestRefusalException>(
                () => Bt1TestClient.HandshakeAsync(clientSide, secret, ClientId, ListenerId, Epoch));
            Assert.Equal(Bt1ErrorCodes.RateLimited, refusal.Code);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal(Bt1ErrorCodes.RateLimited, outcome.ErrorCode);
        }
    }

    [Fact]
    public async Task APeerClaimingTheListenersOwnIdIsRefused()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret)));
            var refusal = await Assert.ThrowsAsync<Bt1TestRefusalException>(
                () => Bt1TestClient.HandshakeAsync(clientSide, secret, ListenerId, ListenerId, Epoch));
            Assert.Equal(Bt1ErrorCodes.AuthFailed, refusal.Code);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal("self_connection", outcome.Reason);
        }
    }

    [Fact]
    public async Task NonHelloFirstMessageIsASchemaViolation()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret)));
            await Bt1StreamFrames.WriteHandshakeFrameAsync(
                clientSide,
                Bt1HandshakeCodec.SerializeAuth(Bt1Role.Client, new byte[Bt1AuthProof.ProofLength]),
                CancellationToken.None);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal(Bt1ErrorCodes.SchemaViolation, outcome.ErrorCode);
        }
    }

    [Fact]
    public async Task UnsupportedChannelVersionIsRefusedWithTheStableCode()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (clientSide)
        using (listenerSide)
        {
            var listenerTask = RunListenerAsync(listenerSide, LookupReturning(Pairing(secret)));
            var nonce = Convert.ToBase64String(new byte[32]).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var badHello =
                $$"""{"kind":"bt1_client_hello","version":2,"device_id":"{{ClientId:D}}","trust_epoch":{{Epoch}},"nonce":"{{nonce}}"}""";
            await Bt1StreamFrames.WriteHandshakeFrameAsync(clientSide, badHello, CancellationToken.None);
            var outcome = Assert.IsType<Bt1ListenerHandshakeOutcome.Refused>(await listenerTask);
            Assert.Equal(Bt1ErrorCodes.VersionUnsupported, outcome.ErrorCode);
        }
    }

    [Fact]
    public async Task AClientThatClosesImmediatelyReadsAsPeerClosed()
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var (clientSide, listenerSide) = BluetoothTestInfrastructure.CreateDuplexPair();
        using (listenerSide)
        {
            clientSide.Dispose();
            var outcome = await RunListenerAsync(listenerSide, LookupReturning(Pairing(secret)));
            Assert.IsType<Bt1ListenerHandshakeOutcome.PeerClosed>(outcome);
        }
    }
}
