using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class AdbPairingQrPayloadTests
{
    [Fact]
    public void QrTextUsesTheAndroidWirelessDebuggingFormat()
    {
        var payload = new AdbPairingQrPayload("clipsync-abc123", "p4ssw0rd");

        Assert.Equal("WIFI:T:ADB;S:clipsync-abc123;P:p4ssw0rd;;", payload.ToQrText());
    }

    [Fact]
    public void ReservedCharactersAreEscaped()
    {
        var payload = new AdbPairingQrPayload("a;b", @"p:w,x\y""z");

        Assert.Equal(@"WIFI:T:ADB;S:a\;b;P:p\:w\,x\\y\""z;;", payload.ToQrText());
    }

    [Fact]
    public void GeneratedPayloadRoundTripsThroughParse()
    {
        var payload = AdbPairingQrPayload.Create();

        Assert.True(AdbPairingQrPayload.TryParse(payload.ToQrText(), out var parsed));
        Assert.Equal(payload, parsed);
    }

    [Fact]
    public void EscapedPayloadRoundTripsThroughParse()
    {
        var payload = new AdbPairingQrPayload("a;b:c", @"p\q,r""s");

        Assert.True(AdbPairingQrPayload.TryParse(payload.ToQrText(), out var parsed));
        Assert.Equal(payload, parsed);
    }

    [Fact]
    public void CreateUsesTheClipSyncServicePrefixAndFreshSecrets()
    {
        var first = AdbPairingQrPayload.Create();
        var second = AdbPairingQrPayload.Create();

        Assert.StartsWith(AdbPairingQrPayload.ServiceNamePrefix, first.ServiceName, StringComparison.Ordinal);
        Assert.True(first.Password.Length >= 10);
        // Two sessions must never share a secret: a shown-then-cancelled QR stays dead.
        Assert.NotEqual(first.Password, second.Password);
        Assert.NotEqual(first.ServiceName, second.ServiceName);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("WIFI:T:WPA;S:homelan;P:secret;;")] // a Wi-Fi network QR, not an ADB pairing one
    [InlineData("WIFI:T:ADB;S:only-name;;")] // missing password
    [InlineData("WIFI:T:ADB;P:only-password;;")] // missing service name
    [InlineData("https://example.com")] // not a WIFI payload at all
    public void RejectsForeignOrIncompletePayloads(string? text) =>
        Assert.False(AdbPairingQrPayload.TryParse(text, out _));
}
