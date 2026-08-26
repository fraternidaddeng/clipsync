using ClipSync.App.Ui;

namespace ClipSync.App.Tests.Ui;

/// <summary>
/// The machine-voice pill labels (user verdict 2026-08-26): encoding / dimensions /
/// byte size render as quiet annotations, and unknown facts yield no label at all —
/// the UI hides the pill instead of showing "?" placeholders.
/// </summary>
public sealed class ImageMetadataTests
{
    [Theory]
    [InlineData("image/png", "PNG")]
    [InlineData("image/jpeg", "JPEG")]
    [InlineData("png", "PNG")]
    [InlineData(null, "")]
    [InlineData("", "")]
    [InlineData("   ", "")]
    public void FormatLabelIsTheUppercasedSubtype(string? mime, string expected) =>
        Assert.Equal(expected, ImageMetadata.FormatLabel(mime));

    [Fact]
    public void DimensionsRequireBothSides()
    {
        Assert.Equal("320×200", ImageMetadata.Dimensions(320, 200));
        Assert.Null(ImageMetadata.Dimensions(null, 200));
        Assert.Null(ImageMetadata.Dimensions(320, null));
    }

    [Theory]
    [InlineData(96, "96 B")]
    [InlineData(1023, "1023 B")]
    [InlineData(1024, "1 KiB")]
    [InlineData(2048, "2 KiB")]
    [InlineData(2400, "2.3 KiB")]
    [InlineData(1024 * 1024, "1 MiB")]
    [InlineData(16 * 1024 * 1024, "16 MiB")]
    public void ByteSizeUsesBinaryUnits(int bytes, string expected) =>
        Assert.Equal(expected, ImageMetadata.ByteSize(bytes));

    [Fact]
    public void ByteSizeIsNullWhenUnknown() => Assert.Null(ImageMetadata.ByteSize(null));

    [Fact]
    public void SummarySkipsUnknownParts()
    {
        Assert.Equal("image/png · 320×200 · 2 KiB", ImageMetadata.Summary("image/png", 320, 200, 2048));
        Assert.Equal("image/png · 96 B", ImageMetadata.Summary("image/png", null, null, 96));
        Assert.Equal("320×200", ImageMetadata.Summary(null, 320, 200, null));
        Assert.Equal(string.Empty, ImageMetadata.Summary(null, null, null, null));
    }
}
