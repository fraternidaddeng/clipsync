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

public static class ImageCodec
{
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
        Span<byte> header = stackalloc byte[Math.Min(checked((int)info.Length), 64 * 1024)];
        using (var stream = File.OpenRead(path))
        {
            var read = stream.Read(header);
            header = header[..read];
        }

        if (!TryReadDimensions(header, out var mime, out var width, out var height)
            && info.Length > header.Length)
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
        else if (!TryReadDimensions(header, out mime, out width, out height))
        {
            return ImageCodecError.UnsupportedMedia;
        }

        if (!MediaLimits.FitsPixelBudget(width, height))
        {
            return ImageCodecError.TooLarge;
        }

        var hash = HashFile(path);
        if (expectedHash is not null && !string.Equals(expectedHash, hash, StringComparison.Ordinal))
        {
            return ImageCodecError.HashMismatch;
        }

        image = new ValidatedImage(mime, hash, checked((int)info.Length), width, height);
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

        var raw = new byte[checked((stride + 1) * height)];
        for (var y = 0; y < height; y++)
        {
            var destRow = y * (stride + 1);
            raw[destRow] = 0;
            var src = bgra.Slice(y * stride, stride);
            for (var x = 0; x < width; x++)
            {
                var srcIndex = x * 4;
                var destIndex = destRow + 1 + (x * 4);
                raw[destIndex] = src[srcIndex + 2];
                raw[destIndex + 1] = src[srcIndex + 1];
                raw[destIndex + 2] = src[srcIndex];
                raw[destIndex + 3] = src[srcIndex + 3];
            }
        }

        using var idat = new MemoryStream();
        using (var deflate = new ZLibStream(idat, CompressionLevel.SmallestSize, leaveOpen: true))
        {
            deflate.Write(raw);
        }

        var compressed = idat.ToArray();
        using var png = new MemoryStream();
        png.Write(PngMagic);
        Span<byte> ihdr = stackalloc byte[13];
        BinaryPrimitives.WriteInt32BigEndian(ihdr, width);
        BinaryPrimitives.WriteInt32BigEndian(ihdr[4..], height);
        ihdr[8] = 8;
        ihdr[9] = 6;
        ihdr[10] = 0;
        ihdr[11] = 0;
        ihdr[12] = 0;
        WriteChunk(png, "IHDR"u8, ihdr);
        WriteChunk(png, "IDAT"u8, compressed);
        WriteChunk(png, "IEND"u8, ReadOnlySpan<byte>.Empty);
        return png.ToArray();
    }

    private static void WriteChunk(Stream stream, ReadOnlySpan<byte> type, ReadOnlySpan<byte> data)
    {
        Span<byte> length = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(length, data.Length);
        stream.Write(length);
        stream.Write(type);
        stream.Write(data);
        Span<byte> crcBuffer = stackalloc byte[type.Length + data.Length];
        type.CopyTo(crcBuffer);
        data.CopyTo(crcBuffer[type.Length..]);
        var crc = Crc32(crcBuffer);
        Span<byte> crcBytes = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(crcBytes, crc);
        stream.Write(crcBytes);
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

    private static uint Crc32(ReadOnlySpan<byte> data)
    {
        var crc = 0xFFFFFFFF;
        foreach (var b in data)
        {
            crc ^= b;
            for (var i = 0; i < 8; i++)
            {
                var mask = (uint)-(crc & 1);
                crc = (crc >> 1) ^ (0xEDB88320 & mask);
            }
        }

        return crc ^ 0xFFFFFFFF;
    }
}
