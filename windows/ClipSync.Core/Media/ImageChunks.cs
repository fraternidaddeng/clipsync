using ClipSync.Core.Protocol;

namespace ClipSync.Core.Media;

public sealed record ImageChunk(int Index, int Count, int ByteCount, string Data);

public static class ImageChunks
{
    public static IReadOnlyList<ImageChunk> Split(ReadOnlySpan<byte> encoded)
    {
        if (encoded.Length is < 1 or > MediaLimits.MaxEncodedBytes)
        {
            throw new ArgumentOutOfRangeException(nameof(encoded), "Encoded image size is out of bounds.");
        }

        var count = (encoded.Length + MediaLimits.MaxChunkBytes - 1) / MediaLimits.MaxChunkBytes;
        if (count > MediaLimits.MaxChunkCount)
        {
            throw new ArgumentOutOfRangeException(nameof(encoded), "Encoded image needs too many chunks.");
        }

        var chunks = new ImageChunk[count];
        for (var index = 0; index < count; index++)
        {
            var start = index * MediaLimits.MaxChunkBytes;
            var length = Math.Min(MediaLimits.MaxChunkBytes, encoded.Length - start);
            chunks[index] = new ImageChunk(
                index,
                count,
                length,
                ProtocolValidation.EncodeBase64Url(encoded.Slice(start, length)));
        }

        return chunks;
    }

    public static bool TryDecodeChunk(string data, int expectedBytes, out byte[] bytes)
    {
        bytes = [];
        if (!ProtocolValidation.TryDecodeBase64Url(data, out var decoded))
        {
            return false;
        }

        if (decoded.Length != expectedBytes
            || expectedBytes is < 1 or > MediaLimits.MaxChunkBytes)
        {
            return false;
        }

        bytes = decoded;
        return true;
    }
}
