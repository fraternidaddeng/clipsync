using System.Buffers.Binary;
using System.Text;
using System.Text.Json;
using ClipSync.Core.Security.Bt1;

namespace ClipSync.Tests.Security;

/// <summary>
/// The bt1 frame layer must be byte-identical with the Android implementation over the
/// shared vectors in protocol/bt1/fixtures/frames/vectors.json, and must fail closed on
/// tampered, replayed, reordered, truncated, and oversized frames.
/// </summary>
public sealed class Bt1FrameCipherTests
{
    private sealed record FrameVector(string Name, byte[] Key, ulong Sequence, byte[] Plaintext, byte[] Frame);

    private static FrameVector[] LoadVectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "protocol-fixtures-bt1", "frames", "vectors.json");
        Assert.True(File.Exists(path), $"Shared bt1 frame vectors are missing: {path}");

        using var document = JsonDocument.Parse(File.ReadAllText(path));
        Assert.Equal("aes-256-gcm", document.RootElement.GetProperty("cipher").GetString());

        return document.RootElement.GetProperty("vectors").EnumerateArray().Select(element => new FrameVector(
            element.GetProperty("name").GetString()!,
            Convert.FromHexString(element.GetProperty("key_hex").GetString()!),
            element.GetProperty("sequence").GetUInt64(),
            Encoding.UTF8.GetBytes(element.GetProperty("plaintext_utf8").GetString()!),
            Convert.FromHexString(element.GetProperty("frame_hex").GetString()!))).ToArray();
    }

    private static byte[] Payload(byte[] frame) => frame[Bt1Frames.LengthPrefixLength..];

    [Fact]
    public void SharedVectorFileContainsAtLeastFiveVectors()
    {
        Assert.True(LoadVectors().Length >= 5);
    }

    [Fact]
    public void EncryptReproducesEverySharedVector()
    {
        foreach (var vector in LoadVectors())
        {
            using var encryptor = new Bt1FrameEncryptor(vector.Key, vector.Sequence);
            var frame = encryptor.EncryptFrame(vector.Plaintext);
            Assert.Equal(Convert.ToHexString(vector.Frame), Convert.ToHexString(frame));
        }
    }

    [Fact]
    public void DecryptReproducesEverySharedVectorPlaintext()
    {
        foreach (var vector in LoadVectors())
        {
            var declaredLength = Bt1Frames.ReadDeclaredPayloadLength(vector.Frame);
            Assert.True(Bt1Frames.IsAcceptableEncryptedPayloadLength(declaredLength), vector.Name);
            Assert.Equal(vector.Frame.Length - Bt1Frames.LengthPrefixLength, declaredLength);

            using var decryptor = new Bt1FrameDecryptor(vector.Key, vector.Sequence);
            Assert.True(decryptor.TryDecryptPayload(Payload(vector.Frame), out var plaintext), vector.Name);
            Assert.Equal(vector.Plaintext, plaintext);
        }
    }

    [Fact]
    public void RoundTripCarriesConsecutiveFramesInOrder()
    {
        var key = new byte[32];
        key[0] = 0x42;
        using var encryptor = new Bt1FrameEncryptor(key);
        using var decryptor = new Bt1FrameDecryptor(key);

        for (var index = 0; index < 5; index++)
        {
            var plaintext = Encoding.UTF8.GetBytes($"frame number {index}");
            Assert.True(decryptor.TryDecryptPayload(Payload(encryptor.EncryptFrame(plaintext)), out var decrypted));
            Assert.Equal(plaintext, decrypted);
        }
    }

    [Fact]
    public void TamperedCiphertextFailsAndPoisonsTheDecryptor()
    {
        var key = new byte[32];
        using var encryptor = new Bt1FrameEncryptor(key);
        using var decryptor = new Bt1FrameDecryptor(key);

        var payload = Payload(encryptor.EncryptFrame("attacker target"u8.ToArray()));
        payload[3] ^= 0x01;
        Assert.False(decryptor.TryDecryptPayload(payload, out _));
        Assert.True(decryptor.HasFailed);

        // Even the untampered original is refused afterwards: failure is fatal.
        payload[3] ^= 0x01;
        Assert.False(decryptor.TryDecryptPayload(payload, out _));
    }

    [Fact]
    public void TamperedTagFails()
    {
        var key = new byte[32];
        using var encryptor = new Bt1FrameEncryptor(key);
        using var decryptor = new Bt1FrameDecryptor(key);

        var payload = Payload(encryptor.EncryptFrame("tag matters"u8.ToArray()));
        payload[^1] ^= 0x80;
        Assert.False(decryptor.TryDecryptPayload(payload, out _));
    }

    [Fact]
    public void ReplayedFrameFailsBecauseTheCounterAdvanced()
    {
        var key = new byte[32];
        using var encryptor = new Bt1FrameEncryptor(key);
        using var decryptor = new Bt1FrameDecryptor(key);

        var payload = Payload(encryptor.EncryptFrame("replay me"u8.ToArray()));
        Assert.True(decryptor.TryDecryptPayload(payload, out _));
        Assert.False(decryptor.TryDecryptPayload(payload, out _));
        Assert.True(decryptor.HasFailed);
    }

    [Fact]
    public void OutOfOrderFramesFail()
    {
        var key = new byte[32];
        using var encryptor = new Bt1FrameEncryptor(key);
        using var decryptor = new Bt1FrameDecryptor(key);

        var first = Payload(encryptor.EncryptFrame("first"u8.ToArray()));
        var second = Payload(encryptor.EncryptFrame("second"u8.ToArray()));

        Assert.False(decryptor.TryDecryptPayload(second, out _));
        Assert.True(decryptor.HasFailed);
        Assert.False(decryptor.TryDecryptPayload(first, out _));
    }

    [Fact]
    public void TruncatedAndUndersizedPayloadsFail()
    {
        var key = new byte[32];
        using var encryptor = new Bt1FrameEncryptor(key);

        var payload = Payload(encryptor.EncryptFrame("truncate me"u8.ToArray()));
        using (var decryptor = new Bt1FrameDecryptor(key))
        {
            Assert.False(decryptor.TryDecryptPayload(payload.AsSpan(0, payload.Length - 1), out _));
        }

        using (var decryptor = new Bt1FrameDecryptor(key))
        {
            // A tag-only payload would imply zero-length plaintext, which bt1 forbids.
            Assert.False(decryptor.TryDecryptPayload(new byte[Bt1Frames.TagLength], out _));
        }
    }

    [Fact]
    public void OversizePlaintextAndDeclaredLengthAreRejected()
    {
        var key = new byte[32];
        using var encryptor = new Bt1FrameEncryptor(key);
        Assert.Throws<ArgumentException>(() => encryptor.EncryptFrame(new byte[Bt1Frames.MaxPlaintextLength + 1]));
        Assert.Throws<ArgumentException>(() => encryptor.EncryptFrame(ReadOnlySpan<byte>.Empty));

        // The receiver rejects an oversize declared length before allocating or decrypting.
        Span<byte> prefix = stackalloc byte[Bt1Frames.LengthPrefixLength];
        BinaryPrimitives.WriteUInt32BigEndian(prefix, (uint)(Bt1Frames.MaxEncryptedPayloadLength + 1));
        Assert.False(Bt1Frames.IsAcceptableEncryptedPayloadLength(Bt1Frames.ReadDeclaredPayloadLength(prefix)));
        BinaryPrimitives.WriteUInt32BigEndian(prefix, uint.MaxValue);
        Assert.False(Bt1Frames.IsAcceptableEncryptedPayloadLength(Bt1Frames.ReadDeclaredPayloadLength(prefix)));

        using var decryptor = new Bt1FrameDecryptor(key);
        Assert.False(decryptor.TryDecryptPayload(new byte[Bt1Frames.MaxEncryptedPayloadLength + 1], out _));
        Assert.True(decryptor.HasFailed);
    }

    [Fact]
    public void DirectionKeysAreNotInterchangeable()
    {
        var vector = LoadVectors()[0];
        var otherKey = (byte[])vector.Key.Clone();
        otherKey[0] ^= 0xFF;

        using var encryptor = new Bt1FrameEncryptor(vector.Key);
        using var decryptor = new Bt1FrameDecryptor(otherKey);
        Assert.False(decryptor.TryDecryptPayload(Payload(encryptor.EncryptFrame("wrong direction"u8.ToArray())), out _));
    }

    [Fact]
    public void SenderCounterExhaustsAfterTheMaximumSequence()
    {
        var key = new byte[32];
        using var encryptor = new Bt1FrameEncryptor(key, ulong.MaxValue);
        _ = encryptor.EncryptFrame("last one"u8.ToArray());
        Assert.Throws<InvalidOperationException>(() => encryptor.EncryptFrame("one too many"u8.ToArray()));
    }

    [Fact]
    public void HandshakeLengthWindowIsEnforced()
    {
        Assert.False(Bt1Frames.IsAcceptableHandshakePayloadLength(0));
        Assert.False(Bt1Frames.IsAcceptableHandshakePayloadLength(1));
        Assert.True(Bt1Frames.IsAcceptableHandshakePayloadLength(2));
        Assert.True(Bt1Frames.IsAcceptableHandshakePayloadLength(4096));
        Assert.False(Bt1Frames.IsAcceptableHandshakePayloadLength(4097));
    }

    [Fact]
    public void WrongKeyLengthIsRejected()
    {
        Assert.Throws<ArgumentException>(() => new Bt1FrameEncryptor(new byte[16]));
        Assert.Throws<ArgumentException>(() => new Bt1FrameDecryptor(new byte[16]));
    }
}
