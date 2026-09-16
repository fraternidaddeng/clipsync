using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

/// <summary>
/// Pure classification behind the card's wireless-session honesty: a serial is wireless
/// when it parses as <c>host:port</c> or is an adb 37+ mDNS connect name, and a previously
/// connected endpoint is Ready / StaleOffline / Vanished strictly from the parsed device rows.
/// </summary>
public sealed class WirelessSessionDiagnosisTests
{
    private static readonly WirelessAdbEndpoint Endpoint = new("192.168.1.10", 40331);

    [Theory]
    [InlineData("192.168.1.10:40331", true)]
    [InlineData("[fe80::1]:40331", true)]
    [InlineData("adb-HUHYEYDQDMVONZDU-MTq7h0._adb-tls-connect._tcp", true)]
    [InlineData("adb-RF8N123456-a._adb-tls-pairing._tcp", true)]
    [InlineData("RF8N123456", false)] // USB serial
    [InlineData("emulator-5554", false)] // dash, no port form
    [InlineData("", false)]
    [InlineData(null, false)]
    public void WirelessSerialsAreHostPortOrMdnsServiceNames(string? serial, bool expected)
    {
        Assert.Equal(expected, WirelessSessionDiagnosis.IsWirelessSerial(serial));
    }

    [Fact]
    public void ReadyEntryClassifiesAsAliveSession()
    {
        var devices = new[] { new AndroidAdbDevice("192.168.1.10:40331", AdbDeviceState.Ready, "Pixel 8") };

        Assert.Equal(WirelessSessionState.Ready, WirelessSessionDiagnosis.Classify(devices, Endpoint));
    }

    [Theory]
    [InlineData(AdbDeviceState.Offline)]
    [InlineData(AdbDeviceState.Unauthorized)]
    [InlineData(AdbDeviceState.Unknown)]
    public void ListedButNotReadyClassifiesAsStale(AdbDeviceState state)
    {
        var devices = new[] { new AndroidAdbDevice("192.168.1.10:40331", state) };

        Assert.Equal(WirelessSessionState.StaleOffline, WirelessSessionDiagnosis.Classify(devices, Endpoint));
    }

    [Fact]
    public void MissingEntryClassifiesAsVanished()
    {
        var devices = new[] { new AndroidAdbDevice("RF8N123456", AdbDeviceState.Ready) };

        Assert.Equal(WirelessSessionState.Vanished, WirelessSessionDiagnosis.Classify(devices, Endpoint));
    }

    [Fact]
    public void StaleWirelessDevicesNamesOnlyNonReadyWirelessEntries()
    {
        var devices = new[]
        {
            new AndroidAdbDevice("192.168.1.10:40331", AdbDeviceState.Offline),
            new AndroidAdbDevice("192.168.1.11:41000", AdbDeviceState.Ready),
            new AndroidAdbDevice("RF8N123456", AdbDeviceState.Offline), // USB offline is not a wireless stale
            new AndroidAdbDevice("adb-OTHER-x._adb-tls-connect._tcp", AdbDeviceState.Offline),
        };

        var stale = WirelessSessionDiagnosis.StaleWirelessDevices(devices);

        Assert.Equal(
            ["192.168.1.10:40331", "adb-OTHER-x._adb-tls-connect._tcp"],
            stale.Select(device => device.Serial));
    }

    [Fact]
    public void Adb37MdnsConnectSerialClassifiesAsAliveWhenTheDialedHostPortIsAbsent()
    {
        // platform-tools 37 lists the session we just connected as the mDNS service name,
        // not the IP:port we passed to `adb connect`. Treating that as Vanished made the
        // card overwrite 「已连接」 with 「无线连接已断开」 on the very next probe.
        var devices = new[]
        {
            new AndroidAdbDevice("adb-HUHYEYDQDMVONZDU-MTq7h0._adb-tls-connect._tcp", AdbDeviceState.Ready, "Redmi"),
        };

        Assert.Equal(WirelessSessionState.Ready, WirelessSessionDiagnosis.Classify(devices, Endpoint));
    }

    [Fact]
    public void OfflineMdnsConnectSerialClassifiesAsStaleNotVanished()
    {
        var devices = new[]
        {
            new AndroidAdbDevice("adb-HUHYEYDQDMVONZDU-MTq7h0._adb-tls-connect._tcp", AdbDeviceState.Offline),
        };

        Assert.Equal(WirelessSessionState.StaleOffline, WirelessSessionDiagnosis.Classify(devices, Endpoint));
    }

    [Fact]
    public void UsbReadyAloneDoesNotCountAsTheWirelessSession()
    {
        var devices = new[] { new AndroidAdbDevice("RF8N123456", AdbDeviceState.Ready) };

        Assert.Equal(WirelessSessionState.Vanished, WirelessSessionDiagnosis.Classify(devices, Endpoint));
    }
}
