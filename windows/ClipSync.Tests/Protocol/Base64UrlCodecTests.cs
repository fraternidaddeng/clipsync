using ClipSync.Core.Protocol;

namespace ClipSync.Tests.Protocol;

/// <summary>
/// The base64url codec was rewritten to stop allocating ~20x the payload per chunk. The
/// reference implementation below is the previous code, kept verbatim as the oracle: for
/// every input the new code must accept exactly the same strings, decode to the same bytes,
/// and encode to the same text.
/// </summary>
public sealed class Base64UrlCodecTests
{
    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    [InlineData(31)]
    [InlineData(32)]
    [InlineData(33)]
    [InlineData(1_000)]
    [InlineData(256 * 1024)]
    public void EncodeMatchesReferenceAndRoundTrips(int length)
    {
        var bytes = Noise(length, seed: length + 7);
        var encoded = ProtocolValidation.EncodeBase64Url(bytes);

        Assert.Equal(ReferenceEncode(bytes), encoded);
        Assert.True(ProtocolValidation.TryDecodeBase64Url(encoded, out var decoded) == (length > 0));
        Assert.Equal(length > 0 ? bytes : [], decoded);
        Assert.Equal(length > 0, ProtocolValidation.TryGetBase64UrlDecodedLength(encoded, out var decodedLength));
        Assert.Equal(length > 0 ? length : 0, decodedLength);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("A")]
    [InlineData("AAAAA")]
    [InlineData("QQ==")]
    [InlineData("QR")]
    [InlineData("QUI=")]
    [InlineData("QUJ")]
    [InlineData("AB+C")]
    [InlineData("AB/C")]
    [InlineData("AB C")]
    [InlineData("AB\nC")]
    [InlineData("ÄBCD")]
    public void RejectsWhatTheReferenceRejects(string? value)
    {
        Assert.False(ReferenceTryDecode(value, out _));
        Assert.False(ProtocolValidation.TryDecodeBase64Url(value, out var bytes));
        Assert.Empty(bytes);
        Assert.False(ProtocolValidation.TryGetBase64UrlDecodedLength(value, out var decodedLength));
        Assert.Equal(0, decodedLength);
    }

    [Theory]
    [InlineData("QQ", 1)]
    [InlineData("QUI", 2)]
    [InlineData("QUJD", 3)]
    [InlineData("-w", 1)]
    [InlineData("_-8", 2)]
    [InlineData("__-w", 3)]
    public void AcceptsCanonicalInputs(string value, int expectedLength)
    {
        Assert.True(ReferenceTryDecode(value, out var expected));
        Assert.True(ProtocolValidation.TryDecodeBase64Url(value, out var actual));
        Assert.Equal(expected, actual);
        Assert.Equal(expectedLength, actual.Length);
        Assert.True(ProtocolValidation.TryGetBase64UrlDecodedLength(value, out var decodedLength));
        Assert.Equal(expectedLength, decodedLength);
    }

    [Fact]
    public void RandomAlphabetStringsAgreeWithTheReference()
    {
        const string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        var state = 20260903u;
        var accepted = 0;
        for (var round = 0; round < 20_000; round++)
        {
            var length = 1 + (int)(Next(ref state) % 39);
            var chars = new char[length];
            for (var index = 0; index < length; index++)
            {
                chars[index] = alphabet[(int)(Next(ref state) % (uint)alphabet.Length)];
            }

            var value = new string(chars);
            var referenceOk = ReferenceTryDecode(value, out var referenceBytes);
            var actualOk = ProtocolValidation.TryDecodeBase64Url(value, out var actualBytes);
            var lengthOk = ProtocolValidation.TryGetBase64UrlDecodedLength(value, out var decodedLength);

            Assert.Equal(referenceOk, actualOk);
            Assert.Equal(referenceOk, lengthOk);
            Assert.Equal(referenceBytes, actualBytes);
            Assert.Equal(referenceBytes.Length, decodedLength);
            if (referenceOk)
            {
                accepted++;
            }
        }

        // Both canonical and non-canonical trailing bits must have been exercised.
        Assert.InRange(accepted, 2_000, 18_000);
    }

    [Fact]
    public void Base64Url256StillRequiresCanonicalThirtyTwoBytes()
    {
        var bytes = Noise(32, seed: 3);
        var encoded = ProtocolValidation.EncodeBase64Url(bytes);
        Assert.Equal(43, encoded.Length);
        Assert.True(ProtocolValidation.TryDecodeBase64Url256(encoded, out var decoded));
        Assert.Equal(bytes, decoded);

        // Flip the last character's low bits: same length, decodes to the same 32 bytes
        // under a lenient decoder, but no longer canonical.
        var nonCanonical = encoded[..^1] + (encoded[^1] == 'B' ? 'C' : 'B');
        Assert.False(ProtocolValidation.TryDecodeBase64Url256(nonCanonical, out _));
        Assert.False(ProtocolValidation.TryDecodeBase64Url256(encoded + "A", out _));
        Assert.False(ProtocolValidation.TryDecodeBase64Url256(encoded[..^1], out _));
    }

    private static uint Next(ref uint state)
    {
        state = (state * 1664525u) + 1013904223u;
        return state >> 8;
    }

    private static byte[] Noise(int length, int seed)
    {
        var bytes = new byte[length];
        var state = (uint)seed;
        for (var index = 0; index < bytes.Length; index++)
        {
            state = (state * 1664525u) + 1013904223u;
            bytes[index] = (byte)(state >> 24);
        }

        return bytes;
    }

    private static string ReferenceEncode(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static bool ReferenceTryDecode(string? value, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (string.IsNullOrEmpty(value) || value.IndexOfAny(['+', '/', '=']) >= 0)
        {
            return false;
        }

        foreach (var character in value)
        {
            if (character is not ((>= 'A' and <= 'Z') or (>= 'a' and <= 'z') or (>= '0' and <= '9') or '-' or '_'))
            {
                return false;
            }
        }

        var padded = value.Replace('-', '+').Replace('_', '/');
        var remainder = padded.Length % 4;
        if (remainder == 1)
        {
            return false;
        }

        if (remainder > 0)
        {
            padded += remainder == 2 ? "==" : "=";
        }

        try
        {
            var decoded = Convert.FromBase64String(padded);
            if (!string.Equals(ReferenceEncode(decoded), value, StringComparison.Ordinal))
            {
                return false;
            }

            bytes = decoded;
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }
}
