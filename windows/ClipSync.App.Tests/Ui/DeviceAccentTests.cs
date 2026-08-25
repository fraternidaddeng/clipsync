using ClipSync.App.Ui;

namespace ClipSync.App.Tests.Ui;

/// <summary>Neighbour-colour assignment: by pairing order, cycling after five, grey when unknown.</summary>
public sealed class DeviceAccentTests
{
    [Theory]
    [InlineData(0, 1)]
    [InlineData(1, 2)]
    [InlineData(4, 5)]
    [InlineData(5, 1)] // sixth device wraps back to hue 195
    [InlineData(9, 5)]
    public void PairingPositionCyclesThroughFiveHues(int position, int expectedAccent) =>
        Assert.Equal(expectedAccent, DeviceAccent.ForPairingPosition(position));

    [Theory]
    [InlineData(1, DeviceAccentRole.Text, "CsDev1Brush")]
    [InlineData(2, DeviceAccentRole.Background, "CsDev2BgBrush")]
    [InlineData(5, DeviceAccentRole.Line, "CsDev5LineBrush")]
    public void KnownAccentsMapToDeviceBrushes(int accent, DeviceAccentRole role, string expectedKey) =>
        Assert.Equal(expectedKey, DeviceAccent.BrushKey(accent, role));

    [Theory]
    [InlineData(DeviceAccent.None, DeviceAccentRole.Text, "CsText3Brush")]
    [InlineData(DeviceAccent.None, DeviceAccentRole.Background, "CsSurface3Brush")]
    [InlineData(DeviceAccent.None, DeviceAccentRole.Line, "CsLine2Brush")]
    [InlineData(6, DeviceAccentRole.Text, "CsText3Brush")] // out of palette = quiet grey, never a guess
    [InlineData(-1, DeviceAccentRole.Background, "CsSurface3Brush")]
    public void UnknownAccentFallsBackToQuietGrey(int accent, DeviceAccentRole role, string expectedKey) =>
        Assert.Equal(expectedKey, DeviceAccent.BrushKey(accent, role));
}
