using System.Globalization;
using System.Text;

namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// adb on Windows prints localized system errors as UTF-8 (platform-tools), while a GUI
/// process's console code page is the ANSI page (GBK on a Chinese PC). Reading the pipe
/// with that code page turns "由于目标计算机积极拒绝，无法连接。" into CJK mojibake.
/// Older adb still emits the ANSI code page, so invalid UTF-8 falls back to it.
/// </summary>
public static class AdbOutputEncoding
{
    static AdbOutputEncoding()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    public static string Decode(ReadOnlySpan<byte> bytes) => Decode(bytes, SystemAnsi());

    public static string Decode(ReadOnlySpan<byte> bytes, Encoding whenNotUtf8)
    {
        ArgumentNullException.ThrowIfNull(whenNotUtf8);
        if (bytes.IsEmpty)
        {
            return string.Empty;
        }

        var utf8 = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
        try
        {
            return utf8.GetString(bytes);
        }
        catch (DecoderFallbackException)
        {
            return whenNotUtf8.GetString(bytes);
        }
    }

    /// <summary>
    /// A closed wireless-debugging port: Windows 10061 / "connection refused" /
    /// the localized "积极拒绝". Not a lost pairing.
    /// </summary>
    public static bool IsClosedPort(string? detail)
    {
        if (string.IsNullOrWhiteSpace(detail))
        {
            return false;
        }

        return detail.Contains("(10061)", StringComparison.Ordinal)
            || detail.Contains("connection refused", StringComparison.OrdinalIgnoreCase)
            || detail.Contains("actively refused", StringComparison.OrdinalIgnoreCase)
            || detail.Contains("积极拒绝", StringComparison.Ordinal);
    }

    private static Encoding SystemAnsi()
    {
        var codePage = CultureInfo.CurrentCulture.TextInfo.ANSICodePage;
        try
        {
            return Encoding.GetEncoding(codePage);
        }
        catch (ArgumentException)
        {
            return Encoding.UTF8;
        }
    }
}
