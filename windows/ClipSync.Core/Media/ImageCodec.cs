using System.Buffers.Binary;
using System.IO.Compression;
using System.Security.Cryptography;

namespace ClipSync.Core.Media;

public enum ImageCodecError
{
    Ok,
    UnsupportedMedia,
    TooLarge,
    DecodeFailed,
    HashMismatch
}

public sealed record ValidatedImage(
    string MimeType,
    string ContentHash,
    int EncodedBytes,
    int PixelWidth,
    int PixelHeight);

/// <summary>What the container header alone proves about a file: no hash, body never read.</summary>
public sealed record ImageHeader(
    string MimeType,
    long EncodedBytes,
    int PixelWidth,
    int PixelHeight);

public static class ImageCodec
{
    private const int ChunkOverhead = 4 + 4 + 4;
    private static readonly byte[] PngMagic = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
    private static readonly byte[] JpegMagic = [0xFF, 0xD8];

    public static string HashBytes(ReadOnlySpan<byte> bytes) =>
        Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    public static string HashFile(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    public static ImageCodecError TryInspect(
        ReadOnlySpan<byte> encoded,
        out ValidatedImage? image,
        string? expectedHash = null)
    {
        image = null;
        if (encoded.Length is < 24 or > MediaLimits.MaxEncodedBytes)
        {
            return encoded.Length > MediaLimits.MaxEncodedBytes
                ? ImageCodecError.TooLarge
                : ImageCodecError.DecodeFailed;
        }

        if (!TryReadDimensions(encoded, out var mime, out var width, out var height))
        {
            return ImageCodecError.UnsupportedMedia;
        }

        if (!MediaLimits.FitsPixelBudget(width, height))
        {
            return ImageCodecError.TooLarge;
        }

        var hash = HashBytes(encoded);
        if (expectedHash is not null && !string.Equals(expectedHash, hash, StringComparison.Ordinal))
        {
            return ImageCodecError.HashMismatch;
        }

        image = new ValidatedImage(mime, hash, encoded.Length, width, height);
        return ImageCodecError.Ok;
    }

    public static ImageCodecError TryInspectFile(
        string path,
        out ValidatedImage? image,
        string? expectedHash = null,
        long? expectedBytes = null)
    {
        image = null;
        var headerError = TryInspectFileHeader(path, out var header, expectedBytes);
        if (headerError != ImageCodecError.Ok || header is null)
        {
            return headerError;
        }

        var hash = HashFile(path);
        if (expectedHash is not null && !string.Equals(expectedHash, hash, StringComparison.Ordinal))
        {
            return ImageCodecError.HashMismatch;
        }

        image = new ValidatedImage(header.MimeType, hash, checked((int)header.EncodedBytes), header.PixelWidth, header.PixelHeight);
        return ImageCodecError.Ok;
    }

    /// <summary>
    /// Header-only file inspect: size gates, magic, dimensions and the pixel budget, without
    /// hashing or loading the body. Callers that already hold the content hash (a streamed
    /// commit) or only need dimensions (thumbnail sizing) use this and skip a full read.
    /// </summary>
    public static ImageCodecError TryInspectFileHeader(
        string path,
        out ImageHeader? header,
        long? expectedBytes = null)
    {
        header = null;
        var info = new FileInfo(path);
        if (!info.Exists)
        {
            return ImageCodecError.DecodeFailed;
        }

        if (info.Length is < 24 or > MediaLimits.MaxEncodedBytes)
        {
            return info.Length > MediaLimits.MaxEncodedBytes
                ? ImageCodecError.TooLarge
                : ImageCodecError.DecodeFailed;
        }

        if (expectedBytes is not null && info.Length != expectedBytes.Value)
        {
            return ImageCodecError.HashMismatch;
        }

        // Header-only inspect: do not load the full file into memory.
        Span<byte> prefix = stackalloc byte[Math.Min(checked((int)info.Length), 64 * 1024)];
        using (var stream = File.OpenRead(path))
        {
            var read = stream.Read(prefix);
            prefix = prefix[..read];
        }

        if (!TryReadDimensions(prefix, out var mime, out var width, out var height)
            && info.Length > prefix.Length)
        {
            // JPEG SOF may sit past the first 64 KiB for huge tables; read more, still bounded.
            var bounded = (int)Math.Min(info.Length, 1024 * 1024);
            var buffer = new byte[bounded];
            using var stream = File.OpenRead(path);
            var read = stream.Read(buffer);
            if (!TryReadDimensions(buffer.AsSpan(0, read), out mime, out width, out height))
            {
                return ImageCodecError.UnsupportedMedia;
            }
        }
        else if (!TryReadDimensions(prefix, out mime, out width, out height))
        {
            return ImageCodecError.UnsupportedMedia;
        }

        if (!MediaLimits.FitsPixelBudget(width, height))
        {
            return ImageCodecError.TooLarge;
        }

        header = new ImageHeader(mime, info.Length, width, height);
        return ImageCodecError.Ok;
    }

    public static bool TryReadDimensions(
        ReadOnlySpan<byte> encoded,
        out string mime,
        out int width,
        out int height)
    {
        mime = string.Empty;
        width = 0;
        height = 0;
        if (encoded.Length >= PngMagic.Length && encoded[..PngMagic.Length].SequenceEqual(PngMagic))
        {
            if (!TryReadPngSize(encoded, out width, out height))
            {
                return false;
            }

            mime = MediaLimits.MimePng;
            return true;
        }

        if (encoded.Length >= 2 && encoded[0] == JpegMagic[0] && encoded[1] == JpegMagic[1])
        {
            if (!TryReadJpegSize(encoded, out width, out height))
            {
                return false;
            }

            mime = MediaLimits.MimeJpeg;
            return true;
        }

        return false;
    }

    /// <summary>
    /// Encodes a top-down 32-bpp BGRA buffer as a PNG. Used by tests and as a
    /// last-resort encoder when the platform layer already copied pixels out.
    /// </summary>
    public static byte[] EncodePngBgra(int width, int height, ReadOnlySpan<byte> bgra)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(width, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(height, 1);
        if (!MediaLimits.FitsPixelBudget(width, height))
        {
            throw new InvalidDataException("PNG encode exceeded the pixel budget.");
        }

        var stride = checked(width * 4);
        if (bgra.Length < checked(stride * height))
        {
            throw new InvalidDataException("BGRA buffer is shorter than width*height*4.");
        }

        // One filtered scanline (filter byte + RGBA) is swizzled and fed to the compressor
        // per row; the full filtered image is never materialized. Deflate output does not
        // depend on how the input is chunked, so the encoded bytes are unchanged.
        using var idat = new MemoryStream(Math.Max(4096, checked((stride + 1) * height) / 8));
        using (var deflate = new ZLibStream(idat, CompressionLevel.SmallestSize, leaveOpen: true))
        {
            var row = new byte[stride + 1];
            for (var y = 0; y < height; y++)
            {
                var src = bgra.Slice(y * stride, stride);
                for (var x = 0; x < width; x++)
                {
                    var srcIndex = x * 4;
                    var destIndex = 1 + (x * 4);
                    row[destIndex] = src[srcIndex + 2];
                    row[destIndex + 1] = src[srcIndex + 1];
                    row[destIndex + 2] = src[srcIndex];
                    row[destIndex + 3] = src[srcIndex + 3];
                }

                deflate.Write(row);
            }
        }

        var compressed = idat.GetBuffer().AsSpan(0, checked((int)idat.Length));
        Span<byte> ihdr = stackalloc byte[13];
        BinaryPrimitives.WriteInt32BigEndian(ihdr, width);
        BinaryPrimitives.WriteInt32BigEndian(ihdr[4..], height);
        ihdr[8] = 8;
        ihdr[9] = 6;
        ihdr[10] = 0;
        ihdr[11] = 0;
        ihdr[12] = 0;

        var png = new byte[checked(PngMagic.Length + ChunkOverhead + ihdr.Length + ChunkOverhead + compressed.Length + ChunkOverhead)];
        var output = png.AsSpan();
        PngMagic.CopyTo(output);
        var offset = PngMagic.Length;
        offset += WriteChunk(output[offset..], "IHDR"u8, ihdr);
        offset += WriteChunk(output[offset..], "IDAT"u8, compressed);
        offset += WriteChunk(output[offset..], "IEND"u8, ReadOnlySpan<byte>.Empty);
        return offset == png.Length ? png : throw new InvalidOperationException("PNG chunk layout mismatch.");
    }

    /// <summary>Writes length, type, data, CRC into <paramref name="destination"/>; returns the bytes written.</summary>
    private static int WriteChunk(Span<byte> destination, ReadOnlySpan<byte> type, ReadOnlySpan<byte> data)
    {
        BinaryPrimitives.WriteInt32BigEndian(destination, data.Length);
        type.CopyTo(destination[4..]);
        data.CopyTo(destination[8..]);
        var crc = Crc32Update(Crc32Update(0xFFFFFFFF, type), data) ^ 0xFFFFFFFF;
        BinaryPrimitives.WriteUInt32BigEndian(destination[(8 + data.Length)..], crc);
        return ChunkOverhead + data.Length;
    }

    private static bool TryReadPngSize(ReadOnlySpan<byte> encoded, out int width, out int height)
    {
        width = 0;
        height = 0;
        // 8 magic + 4 length + 4 type + 13 IHDR
        if (encoded.Length < 24)
        {
            return false;
        }

        var length = BinaryPrimitives.ReadInt32BigEndian(encoded[8..]);
        if (length != 13)
        {
            return false;
        }

        if (encoded[12] != (byte)'I' || encoded[13] != (byte)'H' || encoded[14] != (byte)'D' || encoded[15] != (byte)'R')
        {
            return false;
        }

        var w = BinaryPrimitives.ReadInt32BigEndian(encoded[16..]);
        var h = BinaryPrimitives.ReadInt32BigEndian(encoded[20..]);
        if (w < 1 || h < 1)
        {
            return false;
        }

        width = w;
        height = h;
        return true;
    }

    private static bool TryReadJpegSize(ReadOnlySpan<byte> encoded, out int width, out int height)
    {
        width = 0;
        height = 0;
        var offset = 2;
        while (offset + 9 <= encoded.Length)
        {
            if (encoded[offset] != 0xFF)
            {
                return false;
            }

            var marker = encoded[offset + 1];
            offset += 2;
            if (marker is 0xD8 or 0xD9 or (>= 0xD0 and <= 0xD7) or 0x01)
            {
                continue;
            }

            if (offset + 2 > encoded.Length)
            {
                return false;
            }

            var segmentLength = BinaryPrimitives.ReadUInt16BigEndian(encoded[offset..]);
            if (segmentLength < 2)
            {
                return false;
            }

            if (marker is 0xC0 or 0xC1 or 0xC2 or 0xC3 or 0xC5 or 0xC6 or 0xC7
                or 0xC9 or 0xCA or 0xCB or 0xCD or 0xCE or 0xCF)
            {
                if (segmentLength < 7 || offset + 7 > encoded.Length)
                {
                    return false;
                }

                var h = BinaryPrimitives.ReadUInt16BigEndian(encoded[(offset + 3)..]);
                var w = BinaryPrimitives.ReadUInt16BigEndian(encoded[(offset + 5)..]);
                if (w < 1 || h < 1)
                {
                    return false;
                }

                width = w;
                height = h;
                return true;
            }

            offset += segmentLength;
        }

        return false;
    }

    private static readonly uint[] Crc32Table = BuildCrc32Table();

    private static uint[] BuildCrc32Table()
    {
        var table = new uint[256];
        for (uint n = 0; n < table.Length; n++)
        {
            var c = n;
            for (var k = 0; k < 8; k++)
            {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
            }

            table[n] = c;
        }

        return table;
    }

    /// <summary>Standard PNG CRC-32 (reflected, poly 0xEDB88320); caller pre-conditions and finalizes with 0xFFFFFFFF.</summary>
    private static uint Crc32Update(uint crc, ReadOnlySpan<byte> data)
    {
        var table = Crc32Table;
        foreach (var b in data)
        {
            crc = table[(byte)(crc ^ b)] ^ (crc >> 8);
        }

        return crc;
    }
}
