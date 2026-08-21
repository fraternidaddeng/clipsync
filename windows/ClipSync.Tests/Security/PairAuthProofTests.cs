using System.Text.Json;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;

namespace ClipSync.Tests.Security;

public sealed class PairAuthProofTests
{
    private sealed record AuthVector(
        string Name,
        string PairSecretHex,
        string ChallengeRequestId,
        string NonceBase64Url,
        string ChallengerDeviceId,
        string ResponderDeviceId,
        long TrustEpoch,
        string ProofBase64Url,
        int ProtocolVersion = 1);

    private static AuthVector[] LoadVectors(string fixtureFolder = "protocol-fixtures")
    {
        var path = Path.Combine(AppContext.BaseDirectory, fixtureFolder, "auth", "vectors.json");
        Assert.True(File.Exists(path), $"Shared auth vectors are missing: {path}");

        using var document = JsonDocument.Parse(File.ReadAllText(path));
        Assert.Equal("hmac-sha256", document.RootElement.GetProperty("algorithm").GetString());

        return document.RootElement.GetProperty("vectors").EnumerateArray().Select(element => new AuthVector(
            element.GetProperty("name").GetString()!,
            element.GetProperty("pair_secret_hex").GetString()!,
            element.GetProperty("challenge_request_id").GetString()!,
            element.GetProperty("nonce_base64url").GetString()!,
            element.GetProperty("challenger_device_id").GetString()!,
            element.GetProperty("responder_device_id").GetString()!,
            element.GetProperty("trust_epoch").GetInt64(),
            element.GetProperty("proof_base64url").GetString()!,
            element.TryGetProperty("protocol_version", out var version) ? version.GetInt32() : 1)).ToArray();
    }

    [Fact]
    public void SharedVectorFileContainsAtLeastThreeVectors()
    {
        Assert.True(LoadVectors().Length >= 3);
    }

    [Fact]
    public void ComputeReproducesEverySharedVector()
    {
        foreach (var vector in LoadVectors())
        {
            Assert.True(ProtocolValidation.TryDecodeBase64Url256(vector.NonceBase64Url, out var nonce), vector.Name);

            var proof = PairAuthProof.Compute(
                Convert.FromHexString(vector.PairSecretHex),
                Guid.ParseExact(vector.ChallengeRequestId, "D"),
                nonce,
                Guid.ParseExact(vector.ChallengerDeviceId, "D"),
                Guid.ParseExact(vector.ResponderDeviceId, "D"),
                vector.TrustEpoch,
                vector.ProtocolVersion);

            Assert.Equal(vector.ProofBase64Url, ProtocolValidation.EncodeBase64Url(proof));
        }
    }

    [Fact]
    public void VerifyAcceptsMatchingProofAndRejectsTamperedInputs()
    {
        var vector = LoadVectors()[0];
        Assert.True(ProtocolValidation.TryDecodeBase64Url256(vector.NonceBase64Url, out var nonce));
        Assert.True(ProtocolValidation.TryDecodeBase64Url256(vector.ProofBase64Url, out var proof));
        var secret = Convert.FromHexString(vector.PairSecretHex);
        var requestId = Guid.ParseExact(vector.ChallengeRequestId, "D");
        var challenger = Guid.ParseExact(vector.ChallengerDeviceId, "D");
        var responder = Guid.ParseExact(vector.ResponderDeviceId, "D");

        Assert.True(PairAuthProof.Verify(secret, requestId, nonce, challenger, responder, vector.TrustEpoch, proof, vector.ProtocolVersion));

        Assert.False(PairAuthProof.Verify(secret, requestId, nonce, challenger, responder, vector.TrustEpoch + 1, proof));
        Assert.False(PairAuthProof.Verify(secret, Guid.NewGuid(), nonce, challenger, responder, vector.TrustEpoch, proof));
        Assert.False(PairAuthProof.Verify(secret, requestId, nonce, responder, challenger, vector.TrustEpoch, proof));

        var wrongSecret = (byte[])secret.Clone();
        wrongSecret[0] ^= 0x01;
        Assert.False(PairAuthProof.Verify(wrongSecret, requestId, nonce, challenger, responder, vector.TrustEpoch, proof));

        var wrongNonce = (byte[])nonce.Clone();
        wrongNonce[31] ^= 0x80;
        Assert.False(PairAuthProof.Verify(secret, requestId, wrongNonce, challenger, responder, vector.TrustEpoch, proof));

        Assert.False(PairAuthProof.Verify(secret, requestId, nonce, challenger, responder, vector.TrustEpoch, proof.AsSpan(0, 16)));
    }

    [Fact]
    public void ComputeReproducesEverySharedV2Vector()
    {
        var vectors = LoadVectors("protocol-fixtures-v2");
        Assert.True(vectors.Length >= 3);
        foreach (var vector in vectors)
        {
            Assert.Equal(2, vector.ProtocolVersion);
            Assert.True(ProtocolValidation.TryDecodeBase64Url256(vector.NonceBase64Url, out var nonce), vector.Name);
            var proof = PairAuthProof.Compute(
                Convert.FromHexString(vector.PairSecretHex),
                Guid.ParseExact(vector.ChallengeRequestId, "D"),
                nonce,
                Guid.ParseExact(vector.ChallengerDeviceId, "D"),
                Guid.ParseExact(vector.ResponderDeviceId, "D"),
                vector.TrustEpoch,
                vector.ProtocolVersion);
            Assert.Equal(vector.ProofBase64Url, ProtocolValidation.EncodeBase64Url(proof));
        }
    }

    [Fact]
    public void ComputeRejectsWrongSecretOrNonceLength()
    {
        Assert.Throws<ArgumentException>(() => PairAuthProof.Compute(
            new byte[16], Guid.NewGuid(), new byte[32], Guid.NewGuid(), Guid.NewGuid(), 1));
        Assert.Throws<ArgumentException>(() => PairAuthProof.Compute(
            new byte[32], Guid.NewGuid(), new byte[16], Guid.NewGuid(), Guid.NewGuid(), 1));
    }
}
