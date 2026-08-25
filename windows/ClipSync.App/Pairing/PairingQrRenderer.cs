using QRCoder;

namespace ClipSync.App.Pairing;

/// <summary>Renders the serialized QR payload as a PNG, kept separate for testability.</summary>
public static class PairingQrRenderer
{
    public static byte[] RenderPng(string payloadJson, int pixelsPerModule = 8)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(payloadJson);
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payloadJson, QRCodeGenerator.ECCLevel.M);
        using var png = new PngByteQRCode(data);
        return png.GetGraphic(pixelsPerModule);
    }

    /// <summary>
    /// Renders the payload so every QR module covers a whole number of physical pixels at
    /// the given DPI scale (ui-gap-audit P3: a fixed 8px module blurs at 125%/150%). The
    /// caller lays the image out at exactly <see cref="RenderedQr.PixelEdge"/> ÷
    /// <paramref name="pixelsPerDip"/> device-independent units so WPF never resamples.
    /// </summary>
    public static RenderedQr RenderPngForDpi(string payloadJson, double pixelsPerDip, double targetEdgeDips)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(payloadJson);
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payloadJson, QRCodeGenerator.ECCLevel.M);
        // ModuleMatrix already carries the quiet-zone border, so its count is the drawn edge.
        var moduleCount = data.ModuleMatrix.Count;
        var pixelsPerModule = PixelsPerModule(pixelsPerDip, moduleCount, targetEdgeDips);
        using var png = new PngByteQRCode(data);
        return new RenderedQr(png.GetGraphic(pixelsPerModule), moduleCount * pixelsPerModule);
    }

    /// <summary>
    /// Whole physical pixels per module nearest the target edge, never below one. Payload
    /// growth (more hosts → higher QR version → more modules) shrinks the module, not the code.
    /// </summary>
    public static int PixelsPerModule(double pixelsPerDip, int moduleCount, double targetEdgeDips)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(moduleCount);
        var targetPhysicalEdge = targetEdgeDips * pixelsPerDip;
        return Math.Max(1, (int)Math.Round(targetPhysicalEdge / moduleCount));
    }

    /// <summary>Groups a lowercase hex fingerprint into readable four-character blocks.</summary>
    public static string FormatFingerprint(string fingerprint)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(fingerprint);
        return string.Join(' ', fingerprint.Chunk(4).Select(chunk => new string(chunk)));
    }
}

/// <summary>A rendered QR PNG and its exact square edge in physical pixels.</summary>
public readonly record struct RenderedQr(byte[] Png, int PixelEdge);
