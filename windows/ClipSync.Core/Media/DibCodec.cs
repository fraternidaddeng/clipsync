using System.Buffers.Binary;

namespace ClipSync.Core.Media;

/// <summary>
/// Bounded CF_DIB / CF_DIBV5 decoder. Copies pixels into BGRA then PNG;
/// never keeps the source HGLOBAL. Rejects compression, huge palettes, and
/// integer overflow before allocating the pixel buffer.
/// </summary>
public static class DibCodec
{
    private const int BitmapInfoHeaderSize = 40;
    private const int BitmapV5HeaderSize = 124;
    private const uint BiRgb = 0;
    private const uint BiBitfields = 3;

    public static ImageCodecError TryDecodeToPng(ReadOnlySpan<byte> dib, out byte[] png, out string? pixelDigest)
    {
        png = [];
        pixelDigest = null;
        if (!TryDecodeBgra(dib, out var width, out var height, out var bgra))
        {
            return ImageCodecError.UnsupportedMedia;
        }

        if (!MediaLimits.FitsPixelBudget(width, height))
        {
            return ImageCodecError.TooLarge;
        }

        try
        {
            png = ImageCodec.EncodePngBgra(width, height, bgra);
        }
        catch (InvalidDataException)
        {
            return ImageCodecError.TooLarge;
        }

        pixelDigest = ImageCodec.HashBytes(bgra);
        return ImageCodecError.Ok;
    }

    public static byte[] EncodeDibBgra(int width, int height, ReadOnlySpan<byte> bgra, bool topDown = true)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(width, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(height, 1);
        if (!MediaLimits.FitsPixelBudget(width, height))
        {
            throw new InvalidDataException("DIB encode exceeded the pixel budget.");
        }

        var stride = AlignDword(checked(width * 4));
        var pixelBytes = checked(stride * height);
        var header = BitmapInfoHeaderSize;
        var encoded = new byte[checked(header + pixelBytes)];
        BinaryPrimitives.WriteInt32LittleEndian(encoded.AsSpan(0, 4), header);
        BinaryPrimitives.WriteInt32LittleEndian(encoded.AsSpan(4, 4), width);
        BinaryPrimitives.WriteInt32LittleEndian(encoded.AsSpan(8, 4), topDown ? -height : height);
        BinaryPrimitives.WriteInt16LittleEndian(encoded.AsSpan(12, 2), 1);
        BinaryPrimitives.WriteInt16LittleEndian(encoded.AsSpan(14, 2), 32);
        BinaryPrimitives.WriteInt32LittleEndian(encoded.AsSpan(16, 4), (int)BiRgb);
        BinaryPrimitives.WriteInt32LittleEndian(encoded.AsSpan(20, 4), pixelBytes);

        var srcStride = checked(width * 4);
        for (var y = 0; y < height; y++)
        {
            var destY = topDown ? y : height - 1 - y;
            var dest = encoded.AsSpan(header + (destY * stride), srcStride);
            bgra.Slice(y * srcStride, srcStride).CopyTo(dest);
        }

        return encoded;
    }

    public static bool TryDecodeBgra(ReadOnlySpan<byte> dib, out int width, out int height, out byte[] bgra)
    {
        width = 0;
        height = 0;
        bgra = [];
        if (dib.Length < BitmapInfoHeaderSize)
        {
            return false;
        }

        var headerSize = BinaryPrimitives.ReadInt32LittleEndian(dib);
        if (headerSize is not (BitmapInfoHeaderSize or >= BitmapV5HeaderSize) || headerSize > dib.Length)
        {
            return false;
        }

        var rawWidth = BinaryPrimitives.ReadInt32LittleEndian(dib[4..]);
        var rawHeight = BinaryPrimitives.ReadInt32LittleEndian(dib[8..]);
        var planes = BinaryPrimitives.ReadInt16LittleEndian(dib[12..]);
        var bitCount = BinaryPrimitives.ReadInt16LittleEndian(dib[14..]);
        var compression = BinaryPrimitives.ReadUInt32LittleEndian(dib[16..]);
        if (planes != 1 || rawWidth < 1)
        {
            return false;
        }

        var topDown = rawHeight < 0;
        var absHeight = topDown ? -rawHeight : rawHeight;
        if (absHeight < 1 || !MediaLimits.FitsPixelBudget(rawWidth, absHeight))
        {
            return false;
        }

        if (compression is not (BiRgb or BiBitfields))
        {
            return false;
        }

        if (bitCount is not (24 or 32))
        {
            return false;
        }

        if (compression == BiBitfields && bitCount != 32)
        {
            return false;
        }

        var paletteEntries = 0;
        if (bitCount <= 8)
        {
            return false;
        }

        var masksBytes = compression == BiBitfields ? 12 : 0;
        if (headerSize >= BitmapV5HeaderSize)
        {
            masksBytes = 0;
        }

        long pixelOffset = headerSize + masksBytes + (paletteEntries * 4);
        if (pixelOffset < headerSize || pixelOffset >= dib.Length)
        {
            return false;
        }

        var bytesPerPixel = bitCount / 8;
        var stride = AlignDword(checked((long)rawWidth * bytesPerPixel));
        var expected = pixelOffset + (stride * absHeight);
        if (expected > dib.Length || expected > MediaLimits.MaxEncodedBytes * 2L)
        {
            return false;
        }

        width = rawWidth;
        height = absHeight;
        bgra = new byte[checked(width * height * 4)];
        var src = dib[(int)pixelOffset..];
        for (var y = 0; y < height; y++)
        {
            var srcY = topDown ? y : height - 1 - y;
            var row = src.Slice(checked(srcY * (int)stride), checked(width * bytesPerPixel));
            for (var x = 0; x < width; x++)
            {
                var dest = ((y * width) + x) * 4;
                if (bitCount == 32)
                {
                    bgra[dest] = row[x * 4];
                    bgra[dest + 1] = row[(x * 4) + 1];
                    bgra[dest + 2] = row[(x * 4) + 2];
                    bgra[dest + 3] = row[(x * 4) + 3] == 0 ? (byte)255 : row[(x * 4) + 3];
                }
                else
                {
                    bgra[dest] = row[x * 3];
                    bgra[dest + 1] = row[(x * 3) + 1];
                    bgra[dest + 2] = row[(x * 3) + 2];
                    bgra[dest + 3] = 255;
                }
            }
        }

        return true;
    }

    private static int AlignDword(long bytes)
    {
        var aligned = (bytes + 3) & ~3L;
        return checked((int)aligned);
    }
}
