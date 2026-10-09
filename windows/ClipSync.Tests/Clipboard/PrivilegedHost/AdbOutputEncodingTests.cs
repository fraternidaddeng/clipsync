using System.Text;
using ClipSync.Core.Clipboard.PrivilegedHost;

namespace ClipSync.Tests.Clipboard.PrivilegedHost;

public sealed class AdbOutputEncodingTests
{
    private const string Refused = "由于目标计算机积极拒绝，无法连接。";

    [Fact]
    public void Utf8SystemErrorStaysChineseInsteadOfGbkMojibake()
    {
        var bytes = Encoding.UTF8.GetBytes(
            "cannot connect to 192.168.2.250:46487: " + Refused + " (10061)\n");
        var text = AdbOutputEncoding.Decode(bytes);
        Assert.Contains(Refused, text, StringComparison.Ordinal);
        Assert.Contains("(10061)", text, StringComparison.Ordinal);
        Assert.DoesNotContain("鑾", text, StringComparison.Ordinal);
    }

    [Fact]
    public void InvalidUtf8FallsBackToTheSuppliedAnsiEncoding()
    {
        var gbk = Encoding.GetEncoding(936);
        var bytes = gbk.GetBytes("cannot connect: " + Refused + " (10061)");
        var text = AdbOutputEncoding.Decode(bytes, gbk);
        Assert.Contains(Refused, text, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("cannot connect to 192.168.2.250:46487: 由于目标计算机积极拒绝，无法连接。 (10061)")]
    [InlineData("failed to connect to '192.168.1.10:40331': Connection refused")]
    [InlineData("No connection could be made because the target machine actively refused it.")]
    public void ClosedPortMarkersAreRecognized(string detail)
    {
        Assert.True(AdbOutputEncoding.IsClosedPort(detail));
    }

    [Fact]
    public void OtherRefusalsAreNotTreatedAsAClosedPort()
    {
        Assert.False(AdbOutputEncoding.IsClosedPort("failed to authenticate to 192.168.1.10:40331"));
        Assert.False(AdbOutputEncoding.IsClosedPort(null));
    }
}
