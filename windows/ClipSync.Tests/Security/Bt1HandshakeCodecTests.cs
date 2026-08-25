using System.Text;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security.Bt1;

namespace ClipSync.Tests.Security;

/// <summary>
/// The handshake codec must accept every shared valid fixture and reject every invalid
/// one, exactly like the Android parser over the same files.
/// </summary>
public sealed class Bt1HandshakeCodecTests
{
    private static string FixtureDirectory(string subset) =>
        Path.Combine(AppContext.BaseDirectory, "protocol-fixtures-bt1", "handshake", subset);

    private static TMessage Parse<TMessage>(string subset, string fileName)
        where TMessage : Bt1HandshakeMessage
    {
        var text = File.ReadAllText(Path.Combine(FixtureDirectory(subset), fileName));
        var success = Assert.IsType<Bt1HandshakeParseOutcome.Success>(Bt1HandshakeCodec.Parse(text));
        return Assert.IsType<TMessage>(success.Message);
    }

    [Fact]
    public void EveryValidFixtureParses()
    {
        var files = Directory.GetFiles(FixtureDirectory("valid"), "*.json");
        Assert.NotEmpty(files);
        foreach (var file in files)
        {
            var outcome = Bt1HandshakeCodec.Parse(File.ReadAllText(file));
            Assert.True(outcome is Bt1HandshakeParseOutcome.Success, $"valid fixture rejected: {Path.GetFileName(file)}");
        }
    }

    [Fact]
    public void EveryInvalidFixtureIsRejected()
    {
        var files = Directory.GetFiles(FixtureDirectory("invalid"), "*.json");
        Assert.NotEmpty(files);
        foreach (var file in files)
        {
            var outcome = Bt1HandshakeCodec.Parse(File.ReadAllText(file));
            Assert.True(outcome is Bt1HandshakeParseOutcome.Failure, $"invalid fixture accepted: {Path.GetFileName(file)}");
        }
    }

    [Fact]
    public void ValidFixturesCarryTheExpectedTypedValues()
    {
        var clientHello = Parse<Bt1HandshakeMessage.Hello>("valid", "client-hello.json");
        Assert.Equal(Bt1Role.Client, clientHello.SenderRole);
        Assert.Equal(Guid.ParseExact("11111111-1111-4111-8111-111111111111", "D"), clientHello.DeviceId);
        Assert.Equal(1, clientHello.TrustEpoch);
        Assert.Equal(new byte[32], clientHello.Nonce.ToArray());

        var listenerHello = Parse<Bt1HandshakeMessage.Hello>("valid", "listener-hello.json");
        Assert.Equal(Bt1Role.Listener, listenerHello.SenderRole);
        Assert.Equal(long.MaxValue, listenerHello.TrustEpoch);

        var clientAuth = Parse<Bt1HandshakeMessage.Auth>("valid", "client-auth.json");
        Assert.Equal(Bt1Role.Client, clientAuth.SenderRole);
        Assert.Equal(32, clientAuth.Proof.Length);

        var error = Parse<Bt1HandshakeMessage.ChannelError>("valid", "error-auth-failed.json");
        Assert.Equal(Bt1ErrorCodes.AuthFailed, error.Code);
    }

    [Fact]
    public void UnsupportedVersionGetsItsDedicatedErrorCode()
    {
        var text = File.ReadAllText(Path.Combine(FixtureDirectory("invalid"), "listener-hello-bad-version.json"));
        var failure = Assert.IsType<Bt1HandshakeParseOutcome.Failure>(Bt1HandshakeCodec.Parse(text));
        Assert.Equal(Bt1ErrorCodes.VersionUnsupported, failure.ErrorCode);
    }

    [Fact]
    public void OversizedHandshakePayloadIsRejectedAsTooLarge()
    {
        var padding = new string(' ', Bt1Frames.MaxHandshakePayloadLength);
        var failure = Assert.IsType<Bt1HandshakeParseOutcome.Failure>(
            Bt1HandshakeCodec.Parse(padding + "{}"));
        Assert.Equal(Bt1ErrorCodes.FrameTooLarge, failure.ErrorCode);
    }

    [Fact]
    public void SerializedMessagesRoundTripThroughTheParser()
    {
        var deviceId = Guid.ParseExact("aaaabbbb-cccc-4ddd-8eee-ffff00001111", "D");
        var nonce = Enumerable.Range(0, 32).Select(value => (byte)value).ToArray();

        var helloText = Bt1HandshakeCodec.SerializeHello(Bt1Role.Client, deviceId, 42, nonce);
        var hello = Assert.IsType<Bt1HandshakeMessage.Hello>(
            Assert.IsType<Bt1HandshakeParseOutcome.Success>(Bt1HandshakeCodec.Parse(helloText)).Message);
        Assert.Equal(deviceId, hello.DeviceId);
        Assert.Equal(42, hello.TrustEpoch);
        Assert.Equal(nonce, hello.Nonce.ToArray());

        var proof = Enumerable.Range(0, 32).Select(value => (byte)(value * 3)).ToArray();
        var authText = Bt1HandshakeCodec.SerializeAuth(Bt1Role.Listener, proof);
        var auth = Assert.IsType<Bt1HandshakeMessage.Auth>(
            Assert.IsType<Bt1HandshakeParseOutcome.Success>(Bt1HandshakeCodec.Parse(authText)).Message);
        Assert.Equal(Bt1Role.Listener, auth.SenderRole);
        Assert.Equal(proof, auth.Proof.ToArray());

        var errorText = Bt1HandshakeCodec.SerializeError(Bt1ErrorCodes.RateLimited);
        var error = Assert.IsType<Bt1HandshakeMessage.ChannelError>(
            Assert.IsType<Bt1HandshakeParseOutcome.Success>(Bt1HandshakeCodec.Parse(errorText)).Message);
        Assert.Equal(Bt1ErrorCodes.RateLimited, error.Code);

        // Serialized handshake frames always fit the plaintext handshake window.
        Assert.InRange(Encoding.UTF8.GetByteCount(helloText), Bt1Frames.MinHandshakePayloadLength, Bt1Frames.MaxHandshakePayloadLength);
    }

    [Fact]
    public void SerializerRefusesIllegalInputs()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            Bt1HandshakeCodec.SerializeHello(Bt1Role.Client, Guid.NewGuid(), 0, new byte[32]));
        Assert.Throws<ArgumentException>(() =>
            Bt1HandshakeCodec.SerializeHello(Bt1Role.Client, Guid.NewGuid(), 1, new byte[16]));
        Assert.Throws<ArgumentException>(() =>
            Bt1HandshakeCodec.SerializeAuth(Bt1Role.Client, new byte[31]));
        Assert.Throws<ArgumentException>(() =>
            Bt1HandshakeCodec.SerializeError(Bt1ErrorCodes.DecryptFailed));
    }

    [Fact]
    public void FullHandshakeTranscriptOverSharedVectorOneAuthenticatesBothSides()
    {
        // Drives the four-message sequence of docs/protocol-bt1.md section 3 end to end
        // using handshake vector 1, without any transport: serialize -> parse -> verify
        // both proofs -> derive identical direction keys on both sides.
        var vector = Bt1AuthProofTests.LoadVectors()[0];

        var clientHelloText = Bt1HandshakeCodec.SerializeHello(
            Bt1Role.Client, vector.ClientDeviceId, vector.TrustEpoch, vector.NonceClient);
        var listenerHelloText = Bt1HandshakeCodec.SerializeHello(
            Bt1Role.Listener, vector.ListenerDeviceId, vector.TrustEpoch, vector.NonceListener);

        var clientHello = Assert.IsType<Bt1HandshakeMessage.Hello>(
            Assert.IsType<Bt1HandshakeParseOutcome.Success>(Bt1HandshakeCodec.Parse(clientHelloText)).Message);
        var listenerHello = Assert.IsType<Bt1HandshakeMessage.Hello>(
            Assert.IsType<Bt1HandshakeParseOutcome.Success>(Bt1HandshakeCodec.Parse(listenerHelloText)).Message);

        var clientProof = Bt1AuthProof.Compute(
            vector.PairSecret, Bt1Role.Client, clientHello.Nonce.Span, listenerHello.Nonce.Span,
            clientHello.DeviceId, listenerHello.DeviceId, vector.TrustEpoch);
        var clientAuth = Assert.IsType<Bt1HandshakeMessage.Auth>(
            Assert.IsType<Bt1HandshakeParseOutcome.Success>(
                Bt1HandshakeCodec.Parse(Bt1HandshakeCodec.SerializeAuth(Bt1Role.Client, clientProof))).Message);
        Assert.True(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Client, clientHello.Nonce.Span, listenerHello.Nonce.Span,
            clientHello.DeviceId, listenerHello.DeviceId, vector.TrustEpoch, clientAuth.Proof.Span));
        Assert.Equal(vector.ClientProofBase64Url, ProtocolValidation.EncodeBase64Url(clientAuth.Proof.Span));

        var listenerProof = Bt1AuthProof.Compute(
            vector.PairSecret, Bt1Role.Listener, clientHello.Nonce.Span, listenerHello.Nonce.Span,
            clientHello.DeviceId, listenerHello.DeviceId, vector.TrustEpoch);
        var listenerAuth = Assert.IsType<Bt1HandshakeMessage.Auth>(
            Assert.IsType<Bt1HandshakeParseOutcome.Success>(
                Bt1HandshakeCodec.Parse(Bt1HandshakeCodec.SerializeAuth(Bt1Role.Listener, listenerProof))).Message);
        Assert.True(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Listener, clientHello.Nonce.Span, listenerHello.Nonce.Span,
            clientHello.DeviceId, listenerHello.DeviceId, vector.TrustEpoch, listenerAuth.Proof.Span));

        var clientKeys = Bt1KeySchedule.Derive(vector.PairSecret, clientHello.Nonce.Span, listenerHello.Nonce.Span);
        var listenerKeys = Bt1KeySchedule.Derive(vector.PairSecret, clientHello.Nonce.Span, listenerHello.Nonce.Span);
        Assert.Equal(
            Convert.ToHexString(clientKeys.ClientToListener.Span),
            Convert.ToHexString(listenerKeys.ClientToListener.Span));

        // The channel carries a frame end to end with the derived keys.
        using var encryptor = new Bt1FrameEncryptor(clientKeys.ClientToListener.Span);
        using var decryptor = new Bt1FrameDecryptor(listenerKeys.ClientToListener.Span);
        var frame = encryptor.EncryptFrame("{\"hello\":\"bt1\"}"u8.ToArray());
        Assert.True(decryptor.TryDecryptPayload(frame.AsSpan(Bt1Frames.LengthPrefixLength), out var plaintext));
        Assert.Equal("{\"hello\":\"bt1\"}", Encoding.UTF8.GetString(plaintext));
    }
}
