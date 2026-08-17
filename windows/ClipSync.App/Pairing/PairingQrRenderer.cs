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

    /// <summary>Groups a lowercase hex fingerprint into readable four-character blocks.</summary>
    public static string FormatFingerprint(string fingerprint)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(fingerprint);
        return string.Join(' ', fingerprint.Chunk(4).Select(chunk => new string(chunk)));
    }
}
