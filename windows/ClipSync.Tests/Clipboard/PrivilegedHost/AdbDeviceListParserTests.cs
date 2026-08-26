using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class AdbDeviceListParserTests
{
    [Fact]
    public void EmptyOutputYieldsNoDevices()
    {
        Assert.Empty(AdbDeviceListParser.Parse(null));
        Assert.Empty(AdbDeviceListParser.Parse("   "));
    }

    [Fact]
    public void HeaderAndDaemonChatterAreSkipped()
    {
        const string output =
            "* daemon not running; starting now at tcp:5037\n" +
            "* daemon started successfully\n" +
            "List of devices attached\n";
        Assert.Empty(AdbDeviceListParser.Parse(output));
    }

    [Fact]
    public void ParsesReadyDeviceWithModelHint()
    {
        const string output =
            "List of devices attached\n" +
            "R5CN30ABCDE            device usb:1-1 product:raven model:Pixel_6_Pro device:raven transport_id:3\n";
        var devices = AdbDeviceListParser.Parse(output);
        var device = Assert.Single(devices);
        Assert.Equal("R5CN30ABCDE", device.Serial);
        Assert.Equal(AdbDeviceState.Ready, device.State);
        Assert.Equal("Pixel 6 Pro", device.Model);
        Assert.Equal("Pixel 6 Pro", device.DisplayName);
    }

    [Fact]
    public void MapsUnauthorizedAndOfflineStates()
    {
        const string output =
            "List of devices attached\n" +
            "emulator-5554  offline\n" +
            "ZY223KLMNO     unauthorized\n";
        var devices = AdbDeviceListParser.Parse(output);
        Assert.Equal(2, devices.Count);
        Assert.Equal(AdbDeviceState.Offline, devices[0].State);
        Assert.Equal(AdbDeviceState.Unauthorized, devices[1].State);
        // Without a model hint the serial is the display name.
        Assert.Equal("ZY223KLMNO", devices[1].DisplayName);
    }

    [Fact]
    public void UnknownStateWordSurvivesAsUnknownRatherThanVanishing()
    {
        const string output =
            "List of devices attached\n" +
            "1234abcd   authorizing\n";
        var device = Assert.Single(AdbDeviceListParser.Parse(output));
        Assert.Equal(AdbDeviceState.Unknown, device.State);
    }

    [Fact]
    public void HandlesCrlfLineEndings()
    {
        const string output = "List of devices attached\r\nABC123\tdevice\r\n";
        var device = Assert.Single(AdbDeviceListParser.Parse(output));
        Assert.Equal("ABC123", device.Serial);
        Assert.Equal(AdbDeviceState.Ready, device.State);
    }
}
