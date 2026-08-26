using System.Security.Cryptography;
using System.Text;

namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// The payload behind the wireless-debugging pairing QR code, in the format Android 11+'s
/// "使用二维码配对设备" scanner understands (the same one Android Studio shows):
/// <c>WIFI:T:ADB;S:服务名;P:配对密码;;</c>. The PC generates a random service name and
/// password, shows the QR, and when the phone scans it the phone announces an mDNS service
/// <c>_adb-tls-pairing._tcp</c> named after <see cref="ServiceName"/> — that announcement
/// carries the IP:port the PC must <c>adb pair</c> against with <see cref="Password"/>.
/// The password lives only in the QR pixels and the pair call; it is never logged or shown.
/// </summary>
public sealed record AdbPairingQrPayload(string ServiceName, string Password)
{
    /// <summary>Our service-name prefix, so the mDNS match can never grab another tool's session.</summary>
    public const string ServiceNamePrefix = "clipsync-";

    private const string QrHeader = "WIFI:T:ADB;";

    /// <summary>Alphabet for random parts: unambiguous and needs no QR-format escaping.</summary>
    private const string RandomAlphabet = "abcdefghijkmnpqrstuvwxyz23456789";

    private const int ServiceSuffixLength = 10;
    private const int PasswordLength = 12;

    /// <summary>A fresh, cryptographically random pairing session (new QR = new secret).</summary>
    public static AdbPairingQrPayload Create() =>
        new(ServiceNamePrefix + RandomToken(ServiceSuffixLength), RandomToken(PasswordLength));

    /// <summary>The exact text to encode into the QR image.</summary>
    public string ToQrText() => $"{QrHeader}S:{Escape(ServiceName)};P:{Escape(Password)};;";

    /// <summary>
    /// Parses a <c>WIFI:T:ADB;…</c> payload back into its fields. Used by tests to prove the
    /// generated text round-trips (escaping included); rejects anything that is not an ADB
    /// pairing payload or lacks either field.
    /// </summary>
    public static bool TryParse(string? text, out AdbPairingQrPayload? payload)
    {
        payload = null;
        if (string.IsNullOrWhiteSpace(text) || !text.StartsWith(QrHeader, StringComparison.Ordinal))
        {
            return false;
        }

        string? serviceName = null;
        string? password = null;
        foreach (var (key, value) in SplitFields(text[QrHeader.Length..]))
        {
            switch (key)
            {
                case "S":
                    serviceName = value;
                    break;
                case "P":
                    password = value;
                    break;
                default:
                    break; // Unknown keys are ignored, matching the WIFI-QR convention.
            }
        }

        if (string.IsNullOrEmpty(serviceName) || string.IsNullOrEmpty(password))
        {
            return false;
        }

        payload = new AdbPairingQrPayload(serviceName, password);
        return true;
    }

    /// <summary>Backslash-escapes the WIFI-QR reserved characters (<c>\ ; , : "</c>).</summary>
    private static string Escape(string value)
    {
        var builder = new StringBuilder(value.Length);
        foreach (var ch in value)
        {
            if (ch is '\\' or ';' or ',' or ':' or '"')
            {
                builder.Append('\\');
            }

            builder.Append(ch);
        }

        return builder.ToString();
    }

    /// <summary>Splits <c>K:value;</c> fields honoring backslash escapes.</summary>
    private static IEnumerable<(string Key, string Value)> SplitFields(string body)
    {
        var key = new StringBuilder();
        var value = new StringBuilder();
        var inValue = false;
        for (var i = 0; i < body.Length; i++)
        {
            var ch = body[i];
            if (ch == '\\' && inValue && i + 1 < body.Length)
            {
                value.Append(body[++i]);
            }
            else if (!inValue && ch == ':')
            {
                inValue = true;
            }
            else if (ch == ';')
            {
                if (key.Length > 0)
                {
                    yield return (key.ToString(), value.ToString());
                }

                key.Clear();
                value.Clear();
                inValue = false;
            }
            else if (inValue)
            {
                value.Append(ch);
            }
            else
            {
                key.Append(ch);
            }
        }
    }

    private static string RandomToken(int length)
    {
        var chars = new char[length];
        for (var i = 0; i < length; i++)
        {
            chars[i] = RandomAlphabet[RandomNumberGenerator.GetInt32(RandomAlphabet.Length)];
        }

        return new string(chars);
    }
}
