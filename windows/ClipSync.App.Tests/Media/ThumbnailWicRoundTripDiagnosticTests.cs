using System.Buffers.Binary;
using System.Globalization;
using System.IO;
using System.Text;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ClipSync.App.Media;
using ClipSync.Core.Media;

namespace ClipSync.App.Tests.Media;

/// <summary>
/// TEMPORARY diagnostic for the Windows CI thumbnail failures (Actions run
/// 32827123288): every PNG written by ImageThumbnail's PngBitmapEncoder fails
/// BitmapFile.TryLoad on real Windows, while the hand-rolled ImageCodec PNGs and
/// WIC JPEGs decode fine. This test reproduces the round trip without swallowing
/// exceptions and always fails with a full report so the root cause lands in the
/// CI log. It will be removed in the follow-up commit that fixes the cause.
/// </summary>
public sealed class ThumbnailWicRoundTripDiagnosticTests : IDisposable
{
    private static readonly byte[] PngMagic = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];

    private readonly string root;

    public ThumbnailWicRoundTripDiagnosticTests()
    {
        root = Path.Combine(Path.GetTempPath(), "clipsync-thumb-diag", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
    }

    [Fact]
    public void ReportWicRoundTripBehaviour()
    {
        var report = new StringBuilder();
        report.AppendLine("=== thumbnail WIC round-trip diagnostic ===");

        var store = new MediaBlobStore(root);
        var blobBytes = ImageCodec.EncodePngBgra(320, 200, ImageThumbnailTests.SolidBgra(320, 200, b: 25, g: 130, r: 210));
        var image = store.CommitBytes(blobBytes);
        var blobPath = store.RequirePath(image.ContentHash);

        report.AppendLine("[1] hand-rolled blob chunks: " + DescribeChunks(blobBytes));
        report.AppendLine("[2] blob decode, TryLoad flags, no width: " + Describe(() => DecodeLikeBitmapFile(blobBytes, TryLoadFlags, null)));
        report.AppendLine("[3] blob decode, TryLoad flags, width 128: " + Describe(() => DecodeLikeBitmapFile(blobBytes, TryLoadFlags, 128)));

        // The production write: Ensure decodes the blob and writes the thumbnail PNG.
        var thumbnailPath = ImageThumbnail.Ensure(store, image.ContentHash);
        report.AppendLine("[4] Ensure returned: " + (thumbnailPath ?? "<null>"));
        if (thumbnailPath is not null && File.Exists(thumbnailPath))
        {
            var thumbnailBytes = File.ReadAllBytes(thumbnailPath);
            report.AppendLine("[5] written thumbnail chunks: " + DescribeChunks(thumbnailBytes));
            report.AppendLine("[6] thumb head hex: " + Convert.ToHexString(thumbnailBytes.AsSpan(0, Math.Min(96, thumbnailBytes.Length))));
            report.AppendLine("[7] thumb decode, TryLoad flags, no width: " + Describe(() => DecodeLikeBitmapFile(thumbnailBytes, TryLoadFlags, null)));
            report.AppendLine("[8] thumb decode, TryLoad flags, width 128: " + Describe(() => DecodeLikeBitmapFile(thumbnailBytes, TryLoadFlags, 128)));
            report.AppendLine("[9] thumb decode, no flags, no width: " + Describe(() => DecodeLikeBitmapFile(thumbnailBytes, BitmapCreateOptions.None, null)));
            report.AppendLine("[10] thumb decode, IgnoreColorProfile only: " + Describe(() => DecodeLikeBitmapFile(thumbnailBytes, BitmapCreateOptions.IgnoreColorProfile, null)));
            report.AppendLine("[11] thumb decode, IgnoreImageCache only: " + Describe(() => DecodeLikeBitmapFile(thumbnailBytes, BitmapCreateOptions.IgnoreImageCache, null)));
            report.AppendLine("[12] thumb decode, BitmapDecoder.Create: " + Describe(() => DecodeWithDecoder(thumbnailBytes)));
            report.AppendLine("[13] production BitmapFile.TryLoad(path): " + Describe(() =>
                BitmapFile.TryLoad(thumbnailPath) ?? throw new InvalidOperationException("TryLoad returned null (exception swallowed inside).")));
        }

        // Control: encode straight from a synthetic BitmapSource, bypassing BitmapImage.
        var synthetic = BitmapSource.Create(
            64, 64, 96, 96, PixelFormats.Bgra32, null, ImageThumbnailTests.SolidBgra(64, 64, b: 10, g: 20, r: 30), 64 * 4);
        var syntheticPng = EncodeWithPngBitmapEncoder(synthetic);
        report.AppendLine("[14] synthetic-source PNG chunks: " + DescribeChunks(syntheticPng));
        report.AppendLine("[15] synthetic-source PNG decode, TryLoad flags: " + Describe(() => DecodeLikeBitmapFile(syntheticPng, TryLoadFlags, null)));

        // Control: encode from a BitmapImage decoded like production DecodeBounded does.
        var decodedBlob = DecodeLikeBitmapFile(blobBytes, TryLoadFlags, null);
        var reEncoded = EncodeWithPngBitmapEncoder(decodedBlob);
        report.AppendLine("[16] BitmapImage-source PNG chunks: " + DescribeChunks(reEncoded));
        report.AppendLine("[17] BitmapImage-source PNG decode, TryLoad flags: " + Describe(() => DecodeLikeBitmapFile(reEncoded, TryLoadFlags, null)));

        Assert.Fail(report.ToString());
    }

    private const BitmapCreateOptions TryLoadFlags =
        BitmapCreateOptions.IgnoreColorProfile | BitmapCreateOptions.IgnoreImageCache;

    private static byte[] EncodeWithPngBitmapEncoder(BitmapSource source)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(source));
        using var memory = new MemoryStream();
        encoder.Save(memory);
        return memory.ToArray();
    }

    private static BitmapImage DecodeLikeBitmapFile(byte[] bytes, BitmapCreateOptions options, int? decodePixelWidth)
    {
        using var stream = new MemoryStream(bytes, writable: false);
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.CreateOptions = options;
        image.StreamSource = stream;
        if (decodePixelWidth is > 0)
        {
            image.DecodePixelWidth = decodePixelWidth.Value;
        }

        image.EndInit();
        image.Freeze();
        return image;
    }

    private static BitmapFrame DecodeWithDecoder(byte[] bytes)
    {
        using var stream = new MemoryStream(bytes, writable: false);
        var decoder = BitmapDecoder.Create(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        var frame = decoder.Frames[0];
        frame.Freeze();
        return frame;
    }

    private static string Describe(Func<BitmapSource> attempt)
    {
        try
        {
            var source = attempt();
            var pixel = ImageThumbnailTests.CenterPixel(source);
            return string.Create(
                CultureInfo.InvariantCulture,
                $"OK {source.PixelWidth}x{source.PixelHeight} {source.Format} centerBGRA=({pixel.B},{pixel.G},{pixel.R},{pixel.A})");
        }
        catch (Exception exception)
        {
            return "THREW " + exception;
        }
    }

    private static string DescribeChunks(byte[] png)
    {
        var builder = new StringBuilder();
        builder.Append(CultureInfo.InvariantCulture, $"length={png.Length}");
        if (png.Length < 8 || !png.AsSpan(0, 8).SequenceEqual(PngMagic))
        {
            builder.Append(" <no PNG magic>");
            return builder.ToString();
        }

        var offset = 8;
        while (offset + 8 <= png.Length)
        {
            var chunkLength = BinaryPrimitives.ReadInt32BigEndian(png.AsSpan(offset));
            var type = Encoding.ASCII.GetString(png, offset + 4, 4);
            builder.Append(CultureInfo.InvariantCulture, $" {type}[{chunkLength}]");
            if (chunkLength < 0)
            {
                builder.Append(" <negative length, stopping>");
                break;
            }

            offset += 12 + chunkLength;
        }

        if (offset != png.Length)
        {
            builder.Append(CultureInfo.InvariantCulture, $" <trailing/truncated at {offset}>");
        }

        return builder.ToString();
    }

    public void Dispose()
    {
        if (Directory.Exists(root))
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
