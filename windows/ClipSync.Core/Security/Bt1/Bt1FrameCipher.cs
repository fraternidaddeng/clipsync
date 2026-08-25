using System.Buffers.Binary;
using System.Security.Cryptography;

namespace ClipSync.Core.Security.Bt1;

/// <summary>
/// Sending half of the bt1 frame layer (docs/protocol-bt1.md section 5): AES-256-GCM
/// with a 12-byte counter nonce (4 zero bytes || UINT64_BE(sequence)) starting at 0
/// and advancing by exactly 1 per frame for this direction. Not thread-safe; one
/// instance per direction per connection. The shared reference vectors live in
/// protocol/bt1/fixtures/frames/vectors.json.
/// </summary>
public sealed class Bt1FrameEncryptor : IDisposable
{
    private readonly AesGcm _cipher;
    private ulong _sequence;
    private bool _exhausted;
    private bool _disposed;

    public Bt1FrameEncryptor(ReadOnlySpan<byte> key)
    {
        if (key.Length != Bt1KeySchedule.KeyLength)
        {
            throw new ArgumentException("The direction key must be exactly 32 bytes.", nameof(key));
        }

        _cipher = new AesGcm(key, Bt1Frames.TagLength);
    }

    /// <summary>Visible for tests only: fast-forwards the counter to reproduce fixture sequences.</summary>
    public Bt1FrameEncryptor(ReadOnlySpan<byte> key, ulong startSequence)
        : this(key)
    {
        _sequence = startSequence;
    }

    /// <summary>Encrypts one plaintext into a complete frame including the 4-byte length prefix.</summary>
    public byte[] EncryptFrame(ReadOnlySpan<byte> plaintext)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (plaintext.Length is < 1 or > Bt1Frames.MaxPlaintextLength)
        {
            throw new ArgumentException("Frame plaintext must be 1 byte to 7 MiB.", nameof(plaintext));
        }

        if (_exhausted)
        {
            throw new InvalidOperationException("The bt1 send counter is exhausted; the session must close.");
        }

        Span<byte> nonce = stackalloc byte[Bt1Frames.NonceLength];
        BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], _sequence);

        var frame = new byte[Bt1Frames.LengthPrefixLength + plaintext.Length + Bt1Frames.TagLength];
        BinaryPrimitives.WriteUInt32BigEndian(frame, (uint)(plaintext.Length + Bt1Frames.TagLength));
        _cipher.Encrypt(
            nonce,
            plaintext,
            frame.AsSpan(Bt1Frames.LengthPrefixLength, plaintext.Length),
            frame.AsSpan(Bt1Frames.LengthPrefixLength + plaintext.Length, Bt1Frames.TagLength));

        if (_sequence == ulong.MaxValue)
        {
            _exhausted = true;
        }
        else
        {
            _sequence++;
        }

        return frame;
    }

    public void Dispose()
    {
        if (!_disposed)
        {
            _disposed = true;
            _cipher.Dispose();
        }
    }
}

/// <summary>
/// Receiving half of the bt1 frame layer. The sequence is never transmitted: the
/// receiver decrypts with its own expected counter, so a replayed, reordered,
/// dropped, truncated, or tampered frame fails tag verification. Any failure is
/// fatal and permanently poisons this instance — the caller must close the
/// connection. Not thread-safe; one instance per direction per connection.
/// </summary>
public sealed class Bt1FrameDecryptor : IDisposable
{
    private readonly AesGcm _cipher;
    private ulong _sequence;
    private bool _exhausted;
    private bool _failed;
    private bool _disposed;

    public Bt1FrameDecryptor(ReadOnlySpan<byte> key)
    {
        if (key.Length != Bt1KeySchedule.KeyLength)
        {
            throw new ArgumentException("The direction key must be exactly 32 bytes.", nameof(key));
        }

        _cipher = new AesGcm(key, Bt1Frames.TagLength);
    }

    /// <summary>Visible for tests only: fast-forwards the counter to reproduce fixture sequences.</summary>
    public Bt1FrameDecryptor(ReadOnlySpan<byte> key, ulong startSequence)
        : this(key)
    {
        _sequence = startSequence;
    }

    /// <summary>True once any payload failed; the connection must be closed.</summary>
    public bool HasFailed => _failed;

    /// <summary>
    /// Decrypts one frame payload (the bytes after the length prefix). Returns false —
    /// and permanently fails this decryptor — on any length violation or tag mismatch.
    /// </summary>
    public bool TryDecryptPayload(ReadOnlySpan<byte> payload, out byte[] plaintext)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        plaintext = [];
        if (_failed)
        {
            return false;
        }

        if (!Bt1Frames.IsAcceptableEncryptedPayloadLength(payload.Length) || _exhausted)
        {
            _failed = true;
            return false;
        }

        Span<byte> nonce = stackalloc byte[Bt1Frames.NonceLength];
        BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], _sequence);

        var output = new byte[payload.Length - Bt1Frames.TagLength];
        try
        {
            _cipher.Decrypt(nonce, payload[..^Bt1Frames.TagLength], payload[^Bt1Frames.TagLength..], output);
        }
        catch (CryptographicException)
        {
            _failed = true;
            return false;
        }

        if (_sequence == ulong.MaxValue)
        {
            _exhausted = true;
        }
        else
        {
            _sequence++;
        }

        plaintext = output;
        return true;
    }

    public void Dispose()
    {
        if (!_disposed)
        {
            _disposed = true;
            _cipher.Dispose();
        }
    }
}
