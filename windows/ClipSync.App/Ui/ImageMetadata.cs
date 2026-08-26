using System.Globalization;

namespace ClipSync.App.Ui;

/// <summary>
/// Machine-voice labels for image clip metadata (encoding / dimensions / byte size).
/// The history feedback verdict: pixels are the content hero, so these facts render
/// as small mono annotation pills (tokens §6 机器的声音) — never as the headline line.
/// Locale-neutral by construction: digits, units and the mime subtype need no strings.
/// </summary>
public static class ImageMetadata
{
    /// <summary>"image/png" → "PNG"; unknown or empty mime yields no label at all.</summary>
    public static string FormatLabel(string? mimeType)
    {
        if (string.IsNullOrWhiteSpace(mimeType))
        {
            return string.Empty;
        }

        var slash = mimeType.LastIndexOf('/');
        var subtype = slash >= 0 ? mimeType[(slash + 1)..] : mimeType;
        return subtype.Trim().ToUpperInvariant();
    }

    /// <summary>"320×200"; null when either dimension is unknown (no pill, not "?×?").</summary>
    public static string? Dimensions(int? pixelWidth, int? pixelHeight) =>
        pixelWidth is null || pixelHeight is null
            ? null
            : string.Create(CultureInfo.InvariantCulture, $"{pixelWidth}×{pixelHeight}");

    /// <summary>
    /// "96 B" / "2 KiB" / "1.5 MiB" — binary units matching the 16 MiB wire cap;
    /// null when the byte count is unknown.
    /// </summary>
    public static string? ByteSize(int? encodedBytes)
    {
        if (encodedBytes is not int bytes)
        {
            return null;
        }

        return bytes switch
        {
            < 1024 => string.Create(CultureInfo.InvariantCulture, $"{bytes} B"),
            < 1024 * 1024 => string.Create(CultureInfo.InvariantCulture, $"{bytes / 1024.0:0.#} KiB"),
            _ => string.Create(CultureInfo.InvariantCulture, $"{bytes / (1024.0 * 1024.0):0.#} MiB"),
        };
    }

    /// <summary>
    /// One quiet annotation line for the detail window ("image/png · 320×200 · 2 KiB"),
    /// skipping unknown parts. Empty when nothing is known.
    /// </summary>
    public static string Summary(string? mimeType, int? pixelWidth, int? pixelHeight, int? encodedBytes)
    {
        var parts = new List<string>(3);
        if (!string.IsNullOrWhiteSpace(mimeType))
        {
            parts.Add(mimeType);
        }

        if (Dimensions(pixelWidth, pixelHeight) is string dimensions)
        {
            parts.Add(dimensions);
        }

        if (ByteSize(encodedBytes) is string size)
        {
            parts.Add(size);
        }

        return string.Join(" · ", parts);
    }
}
