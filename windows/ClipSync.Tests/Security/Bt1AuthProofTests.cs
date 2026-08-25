using System.Text.Json;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security.Bt1;

namespace ClipSync.Tests.Security;

/// <summary>
/// The bt1 proof and key schedule must be byte-identical with the Android implementation
/// over the shared vectors in protocol/bt1/fixtures/handshake/vectors.json.
/// </summary>
public sealed class Bt1AuthProofTests
{
    internal sealed record HandshakeVector(
        string Name,
        byte[] PairSecret,
        Guid ClientDeviceId,
        Guid ListenerDeviceId,
        long TrustEpoch,
        byte[] NonceClient,
        byte[] NonceListener,
        string ClientProofBase64Url,
        string ListenerProofBase64Url,
        string KeyClientToListenerHex,
        string KeyListenerToClientHex);

    internal static HandshakeVector[] LoadVectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "protocol-fixtures-bt1", "handshake", "vectors.json");
        Assert.True(File.Exists(path), $"Shared bt1 handshake vectors are missing: {path}");

        using var document = JsonDocument.Parse(File.ReadAllText(path));
        Assert.Equal("hmac-sha256 + hkdf-sha256", document.RootElement.GetProperty("algorithm").GetString());

        return document.RootElement.GetProperty("vectors").EnumerateArray().Select(element =>
        {
            Assert.True(ProtocolValidation.TryDecodeBase64Url256(
                element.GetProperty("nonce_client_base64url").GetString(), out var nonceClient));
            Assert.True(ProtocolValidation.TryDecodeBase64Url256(
                element.GetProperty("nonce_listener_base64url").GetString(), out var nonceListener));
            return new HandshakeVector(
                element.GetProperty("name").GetString()!,
                Convert.FromHexString(element.GetProperty("pair_secret_hex").GetString()!),
                Guid.ParseExact(element.GetProperty("client_device_id").GetString()!, "D"),
                Guid.ParseExact(element.GetProperty("listener_device_id").GetString()!, "D"),
                element.GetProperty("trust_epoch").GetInt64(),
                nonceClient,
                nonceListener,
                element.GetProperty("client_proof_base64url").GetString()!,
                element.GetProperty("listener_proof_base64url").GetString()!,
                element.GetProperty("key_client_to_listener_hex").GetString()!,
                element.GetProperty("key_listener_to_client_hex").GetString()!);
        }).ToArray();
    }

    [Fact]
    public void SharedVectorFileContainsAtLeastThreeVectors()
    {
        Assert.True(LoadVectors().Length >= 3);
    }

    [Fact]
    public void ComputeReproducesBothProofsOfEverySharedVector()
    {
        foreach (var vector in LoadVectors())
        {
            var clientProof = Bt1AuthProof.Compute(
                vector.PairSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
                vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch);
            var listenerProof = Bt1AuthProof.Compute(
                vector.PairSecret, Bt1Role.Listener, vector.NonceClient, vector.NonceListener,
                vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch);

            Assert.Equal(vector.ClientProofBase64Url, ProtocolValidation.EncodeBase64Url(clientProof));
            Assert.Equal(vector.ListenerProofBase64Url, ProtocolValidation.EncodeBase64Url(listenerProof));
        }
    }

    [Fact]
    public void VerifyAcceptsMatchingProofAndRejectsTamperedInputs()
    {
        var vector = LoadVectors()[0];
        Assert.True(ProtocolValidation.TryDecodeBase64Url256(vector.ClientProofBase64Url, out var proof));

        Assert.True(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch, proof));

        // A client proof never verifies as a listener proof (reflection defense).
        Assert.False(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Listener, vector.NonceClient, vector.NonceListener,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch, proof));

        Assert.False(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch + 1, proof));

        // Swapped nonces and swapped device identities both fail.
        Assert.False(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Client, vector.NonceListener, vector.NonceClient,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch, proof));
        Assert.False(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
            vector.ListenerDeviceId, vector.ClientDeviceId, vector.TrustEpoch, proof));

        var wrongSecret = (byte[])vector.PairSecret.Clone();
        wrongSecret[0] ^= 0x01;
        Assert.False(Bt1AuthProof.Verify(
            wrongSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch, proof));

        var tamperedProof = (byte[])proof.Clone();
        tamperedProof[31] ^= 0x80;
        Assert.False(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch, tamperedProof));

        Assert.False(Bt1AuthProof.Verify(
            vector.PairSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch, proof.AsSpan(0, 16)));
    }

    [Fact]
    public void Bt1ProofDiffersFromV1ProofOverTheSameInputs()
    {
        // The bt1 domain prefix must separate the proof domains even for identical key material.
        var vector = LoadVectors()[0];
        var bt1Proof = Bt1AuthProof.Compute(
            vector.PairSecret, Bt1Role.Client, vector.NonceClient, vector.NonceListener,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch);
        var v1Proof = ClipSync.Core.Security.PairAuthProof.Compute(
            vector.PairSecret, vector.ClientDeviceId, vector.NonceClient,
            vector.ClientDeviceId, vector.ListenerDeviceId, vector.TrustEpoch);
        Assert.NotEqual(Convert.ToHexString(v1Proof), Convert.ToHexString(bt1Proof));
    }

    [Fact]
    public void ComputeRejectsWrongSecretOrNonceLengths()
    {
        Assert.Throws<ArgumentException>(() => Bt1AuthProof.Compute(
            new byte[16], Bt1Role.Client, new byte[32], new byte[32], Guid.NewGuid(), Guid.NewGuid(), 1));
        Assert.Throws<ArgumentException>(() => Bt1AuthProof.Compute(
            new byte[32], Bt1Role.Client, new byte[16], new byte[32], Guid.NewGuid(), Guid.NewGuid(), 1));
        Assert.Throws<ArgumentException>(() => Bt1AuthProof.Compute(
            new byte[32], Bt1Role.Client, new byte[32], new byte[16], Guid.NewGuid(), Guid.NewGuid(), 1));
    }

    [Fact]
    public void KeyScheduleReproducesBothDirectionKeysOfEverySharedVector()
    {
        foreach (var vector in LoadVectors())
        {
            var keys = Bt1KeySchedule.Derive(vector.PairSecret, vector.NonceClient, vector.NonceListener);
            Assert.Equal(vector.KeyClientToListenerHex, Convert.ToHexString(keys.ClientToListener.Span).ToLowerInvariant());
            Assert.Equal(vector.KeyListenerToClientHex, Convert.ToHexString(keys.ListenerToClient.Span).ToLowerInvariant());
        }
    }

    [Fact]
    public void KeyScheduleRejectsWrongInputLengths()
    {
        Assert.Throws<ArgumentException>(() => Bt1KeySchedule.Derive(new byte[16], new byte[32], new byte[32]));
        Assert.Throws<ArgumentException>(() => Bt1KeySchedule.Derive(new byte[32], new byte[16], new byte[32]));
        Assert.Throws<ArgumentException>(() => Bt1KeySchedule.Derive(new byte[32], new byte[32], new byte[16]));
    }

    [Fact]
    public void KeysDependOnBothNoncesAndDifferPerDirection()
    {
        var vector = LoadVectors()[0];
        var keys = Bt1KeySchedule.Derive(vector.PairSecret, vector.NonceClient, vector.NonceListener);
        Assert.NotEqual(
            Convert.ToHexString(keys.ClientToListener.Span),
            Convert.ToHexString(keys.ListenerToClient.Span));

        var swapped = Bt1KeySchedule.Derive(vector.PairSecret, vector.NonceListener, vector.NonceClient);
        Assert.NotEqual(
            Convert.ToHexString(keys.ClientToListener.Span),
            Convert.ToHexString(swapped.ClientToListener.Span));
    }
}
