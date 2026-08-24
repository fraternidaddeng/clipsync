using ClipSync.Core.Clipboard;

namespace ClipSync.Tests.Clipboard;

/// <summary>
/// 与 Android 端 ClipContentFormatTest.kt 共用同一张用例表（ADR 0003）：
/// 两端启发式必须逐条镜像，改动任何一条都要同时更新两份测试。
/// </summary>
public sealed class ClipContentClassifierTests
{
    // ---- LINK ----

    [Theory]
    [InlineData("https://github.com/clipsync/core")]
    [InlineData("HTTP://EXAMPLE.COM/PATH?q=1")]
    [InlineData("ftp://host/file.txt")]
    [InlineData("www.example.com")]
    [InlineData("www.example.co.jp/路径")]
    [InlineData("  https://example.com  ")] // 两侧空白先修剪
    public void ClassifiesLinks(string text) =>
        Assert.Equal(ClipContentFormat.Link, ClipContentClassifier.Classify(text));

    [Theory]
    [InlineData("见 https://example.com 详情")] // 正文里内嵌 URL 不算链接
    [InlineData("https://example.com\n第二行")] // 多行不算
    [InlineData("https:// example.com")] // 内含空格不算
    [InlineData("example.com")] // 裸域名不认（误报率太高）
    public void EmbeddedOrBrokenUrlsStayPlain(string text) =>
        Assert.Equal(ClipContentFormat.Plain, ClipContentClassifier.Classify(text));

    // ---- EMAIL ----

    [Theory]
    [InlineData("user@example.com")]
    [InlineData("user.name+tag@example.co.uk")]
    [InlineData("USER_9%x-@sub.domain.org")]
    public void ClassifiesEmails(string text) =>
        Assert.Equal(ClipContentFormat.Email, ClipContentClassifier.Classify(text));

    [Theory]
    [InlineData("user@localhost")] // 没有顶级域
    [InlineData("user @example.com")] // 内含空格
    [InlineData("联系 user@example.com 谢谢")] // 内嵌在正文里
    public void EmailLookalikesStayPlain(string text) =>
        Assert.Equal(ClipContentFormat.Plain, ClipContentClassifier.Classify(text));

    // ---- OTP ----

    [Theory]
    [InlineData("1234")] // 4 位、不在年份区间
    [InlineData("0000")]
    [InlineData("1899")] // 年份区间下界之外
    [InlineData("2100")] // 年份区间上界之外
    [InlineData("843921")]
    [InlineData("12345678")] // 8 位上界
    [InlineData(" 552200 ")] // 修剪后是裸数字
    [InlineData("【淘宝】验证码 843921，五分钟内有效。")]
    [InlineData("【招商银行】\n校验码 552200\n请勿泄露")] // 短信可以多行
    [InlineData("Your Google verification code is G-483921")]
    [InlineData("OTP: 90210")]
    [InlineData("2FA code 4821 expires in 10 minutes")]
    public void ClassifiesOtp(string text) =>
        Assert.Equal(ClipContentFormat.Otp, ClipContentClassifier.Classify(text));

    [Theory]
    [InlineData("2026")] // 独立 4 位数在 1900–2099 更像年份
    [InlineData("1900")] // 年份区间下界
    [InlineData("2099")] // 年份区间上界
    [InlineData("123")] // 太短
    [InlineData("123456789")] // 9 位太长
    [InlineData("13800138000")] // 手机号
    [InlineData("会议室在 3421 号楼")] // 有数字但没有验证码词汇
    [InlineData("发货单号 3421，请查收")] // 同上：数字段在场，词汇缺席
    public void OtpLookalikesStayPlain(string text) =>
        Assert.Equal(ClipContentFormat.Plain, ClipContentClassifier.Classify(text));

    [Fact]
    public void LongTextWithOtpKeywordStaysPlain()
    {
        var text = "验证码 843921 " + new string('x', 120);
        Assert.Equal(ClipContentFormat.Plain, ClipContentClassifier.Classify(text));
    }

    // ---- CREDENTIAL ----

    [Theory]
    [InlineData("Tr0ub4dor&3x!")] // 四类字符
    [InlineData("Passw0rd")] // 三类、下界 8 位
    [InlineData("Xk9#mQ2v")]
    [InlineData("ABCD-1234-efgh")] // 许可证钥样也算口令样
    public void ClassifiesCredentials(string text) =>
        Assert.Equal(ClipContentFormat.Credential, ClipContentClassifier.Classify(text));

    [Theory]
    [InlineData("password")] // 只有一类字符
    [InlineData("Pass word 123")] // 内含空白
    [InlineData("P@ss1")] // 太短
    [InlineData("C:\\Users\\me\\secret.txt")] // 路径样（反斜杠）
    [InlineData("src/Main0/Java.kt")] // 路径样（斜杠）
    [InlineData("550e8400-e29b-41d4-a716-446655440000")] // UUID 例外
    [InlineData("a1b2c3d4e5f6a7b8c9d0")] // 纯十六进制只有两类
    [InlineData("密码是Xk9#mQ2v哦")] // 含 CJK 不算
    public void CredentialLookalikesStayPlain(string text) =>
        Assert.Equal(ClipContentFormat.Plain, ClipContentClassifier.Classify(text));

    [Fact]
    public void OverlongSingleLineStaysPlain()
    {
        var text = "Aa1!" + new string('x', 61); // 65 字符超出口令上界
        Assert.Equal(ClipContentFormat.Plain, ClipContentClassifier.Classify(text));
    }

    // ---- 兜底与优先级 ----

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   \r\n\t ")]
    [InlineData("会议纪要：本周五完成 Stage 4 端到端握手测试")]
    public void EverythingElseIsPlain(string? text) =>
        Assert.Equal(ClipContentFormat.Plain, ClipContentClassifier.Classify(text));

    [Fact]
    public void LinkWinsOverCredentialShapedText()
    {
        // 一个 URL 同时满足口令样三类字符，但判定次序保证它是链接。
        Assert.Equal(ClipContentFormat.Link, ClipContentClassifier.Classify("https://Ab1!x.io"));
    }

    [Fact]
    public void EmailWinsOverCredentialShapedText()
    {
        Assert.Equal(ClipContentFormat.Email, ClipContentClassifier.Classify("A9._%+-b@x.io"));
    }

    [Fact]
    public void BareDigitsWinOverOtpContext()
    {
        // 裸 4–8 位数不需要词汇；词汇分支只服务带上下文的短文本。
        Assert.Equal(ClipContentFormat.Otp, ClipContentClassifier.Classify("77031"));
    }
}
