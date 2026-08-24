using System.Text.RegularExpressions;

namespace ClipSync.Core.Clipboard;

/// <summary>
/// 本地内容形态标签（ADR 0003）。定位是检索辅助：帮人从历史里凭
/// 「这是个链接 / 验证码」的印象把内容找回来，不是安全特性，不做 ML。
/// 规则与 Android 端 <c>ui/home/ClipContentFormat.kt</c> 逐条镜像；
/// 动任何一条启发式都必须同步两端实现、两端单测与
/// <c>docs/adr/0003-local-format-tags.md</c>。标签只在渲染时计算，不落库。
/// </summary>
public enum ClipContentFormat
{
    /// <summary>单行 http / https / ftp / www 地址。</summary>
    Link,

    /// <summary>单行邮箱地址（UI 词汇「账号」）。</summary>
    Email,

    /// <summary>4–8 位验证码：裸数字，或短文本含验证码词汇 + 独立数字段。</summary>
    Otp,

    /// <summary>口令样单行：8–64 个可见 ASCII、无空白、至少三类字符。</summary>
    Credential,

    /// <summary>其余一切。缺省即普通文本。</summary>
    Plain,
}

public static partial class ClipContentClassifier
{
    /// <summary>验证码上下文词汇；英文按小写比较。</summary>
    private static readonly string[] OtpKeywords =
    [
        "验证码", "校验码", "动态码", "动态密码", "取件码", "提取码", "认证码",
        "verification code", "verify code", "security code", "one-time", "otp", "2fa",
    ];

    /// <summary>上下文式验证码只认短信量级的短文本。</summary>
    private const int OtpContextMaxLength = 120;

    // 独立 4 位数落在 1900–2099 更像年份，让给 Plain。
    private const int YearMin = 1900;
    private const int YearMax = 2099;

    private const int CredentialMinLength = 8;
    private const int CredentialMaxLength = 64;

    [GeneratedRegex(@"^(?:https?|ftp)://\S+$", RegexOptions.IgnoreCase)]
    private static partial Regex LinkSchemeRegex();

    [GeneratedRegex(@"^www\.\S+\.\S+$", RegexOptions.IgnoreCase)]
    private static partial Regex LinkWwwRegex();

    // 宽松版邮箱（检索辅助，不做合法性校验）：本地部分@域名.顶级域。
    [GeneratedRegex(@"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$")]
    private static partial Regex EmailRegex();

    [GeneratedRegex(@"^\d{4,8}$")]
    private static partial Regex BareOtpRegex();

    // 独立的 4–8 位数字段：两侧都不是数字（避免匹配长号码的一截）。
    [GeneratedRegex(@"(?<!\d)\d{4,8}(?!\d)")]
    private static partial Regex OtpDigitRunRegex();

    // UUID 不当口令看：开发场景太常见，误报打扰大于收益。
    [GeneratedRegex(@"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")]
    private static partial Regex UuidRegex();

    /// <summary>
    /// 判定次序即优先级：Link → Email → Otp → Credential → Plain。
    /// 前四类都是「越具体越先认」，兜底永远是 Plain——宁可漏标不误标。
    /// </summary>
    public static ClipContentFormat Classify(string? content)
    {
        var text = content?.Trim() ?? string.Empty;
        if (text.Length == 0)
        {
            return ClipContentFormat.Plain;
        }

        var singleLine = !text.Contains('\n') && !text.Contains('\r');
        if (singleLine)
        {
            if (LinkSchemeRegex().IsMatch(text) || LinkWwwRegex().IsMatch(text))
            {
                return ClipContentFormat.Link;
            }

            if (EmailRegex().IsMatch(text))
            {
                return ClipContentFormat.Email;
            }
        }

        if (IsOtp(text))
        {
            return ClipContentFormat.Otp;
        }

        if (singleLine && IsCredentialLike(text))
        {
            return ClipContentFormat.Credential;
        }

        return ClipContentFormat.Plain;
    }

    private static bool IsOtp(string text)
    {
        if (BareOtpRegex().IsMatch(text))
        {
            return !(text.Length == 4 && int.Parse(text) is >= YearMin and <= YearMax);
        }

        // 短信可以多行，所以上下文分支不要求单行，只封顶长度。
        if (text.Length > OtpContextMaxLength)
        {
            return false;
        }

        var lower = text.ToLowerInvariant();
        return OtpKeywords.Any(lower.Contains) && OtpDigitRunRegex().IsMatch(text);
    }

    private static bool IsCredentialLike(string text)
    {
        if (text.Length is < CredentialMinLength or > CredentialMaxLength)
        {
            return false;
        }

        // 只认可见 ASCII：内含空白或中日韩文字的都不是口令。
        if (text.Any(ch => ch is < '\u0021' or > '\u007E'))
        {
            return false;
        }

        // 路径样内容（含斜杠）与 UUID 不当口令看。
        if (text.Contains('/') || text.Contains('\\'))
        {
            return false;
        }

        if (UuidRegex().IsMatch(text))
        {
            return false;
        }

        var classes = 0;
        if (text.Any(ch => ch is >= 'a' and <= 'z')) classes++;
        if (text.Any(ch => ch is >= 'A' and <= 'Z')) classes++;
        if (text.Any(ch => ch is >= '0' and <= '9')) classes++;
        if (text.Any(ch => !char.IsAsciiLetterOrDigit(ch))) classes++;
        return classes >= 3;
    }
}
