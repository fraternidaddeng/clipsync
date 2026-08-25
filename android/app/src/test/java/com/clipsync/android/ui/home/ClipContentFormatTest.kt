package com.clipsync.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与 Windows 端 ClipContentClassifierTests.cs 共用同一张用例表（ADR 0003）：
 * 两端启发式必须逐条镜像，改动任何一条都要同时更新两份测试。
 */
class ClipContentFormatTest {

    private fun assertFormat(expected: ClipContentFormat, vararg texts: String) {
        texts.forEach { text ->
            assertEquals("「$text」", expected, classifyClipContent(text))
        }
    }

    // ---- LINK ----

    @Test
    fun `classifies links`() = assertFormat(
        ClipContentFormat.LINK,
        "https://github.com/clipsync/core",
        "HTTP://EXAMPLE.COM/PATH?q=1",
        "ftp://host/file.txt",
        "www.example.com",
        "www.example.co.jp/路径",
        "  https://example.com  ", // 两侧空白先修剪
    )

    @Test
    fun `embedded or broken urls stay plain`() = assertFormat(
        ClipContentFormat.PLAIN,
        "见 https://example.com 详情", // 正文里内嵌 URL 不算链接
        "https://example.com\n第二行", // 多行不算
        "https:// example.com", // 内含空格不算
        "example.com", // 裸域名不认（误报率太高）
    )

    // ---- EMAIL ----

    @Test
    fun `classifies emails`() = assertFormat(
        ClipContentFormat.EMAIL,
        "user@example.com",
        "user.name+tag@example.co.uk",
        "USER_9%x-@sub.domain.org",
    )

    @Test
    fun `email lookalikes stay plain`() = assertFormat(
        ClipContentFormat.PLAIN,
        "user@localhost", // 没有顶级域
        "user @example.com", // 内含空格
        "联系 user@example.com 谢谢", // 内嵌在正文里
    )

    // ---- OTP ----

    @Test
    fun `classifies otp`() = assertFormat(
        ClipContentFormat.OTP,
        "1234", // 4 位、不在年份区间
        "0000",
        "1899", // 年份区间下界之外
        "2100", // 年份区间上界之外
        "843921",
        "12345678", // 8 位上界
        " 552200 ", // 修剪后是裸数字
        "【淘宝】验证码 843921，五分钟内有效。",
        "【招商银行】\n校验码 552200\n请勿泄露", // 短信可以多行
        "Your Google verification code is G-483921",
        "OTP: 90210",
        "2FA code 4821 expires in 10 minutes",
    )

    @Test
    fun `otp lookalikes stay plain`() = assertFormat(
        ClipContentFormat.PLAIN,
        "2026", // 独立 4 位数在 1900–2099 更像年份
        "1900", // 年份区间下界
        "2099", // 年份区间上界
        "123", // 太短
        "123456789", // 9 位太长
        "13800138000", // 手机号
        "会议室在 3421 号楼", // 有数字但没有验证码词汇
        "发货单号 3421，请查收", // 同上：数字段在场，词汇缺席
        "验证码 843921 " + "x".repeat(120), // 超出短信量级长度
    )

    // ---- CREDENTIAL ----

    @Test
    fun `classifies credentials`() = assertFormat(
        ClipContentFormat.CREDENTIAL,
        "Tr0ub4dor&3x!", // 四类字符
        "Passw0rd", // 三类、下界 8 位
        "Xk9#mQ2v",
        "ABCD-1234-efgh", // 许可证钥样也算口令样
    )

    @Test
    fun `credential lookalikes stay plain`() = assertFormat(
        ClipContentFormat.PLAIN,
        "password", // 只有一类字符
        "Pass word 123", // 内含空白
        "P@ss1", // 太短
        "Aa1!" + "x".repeat(61), // 65 字符超出口令上界
        "C:\\Users\\me\\secret.txt", // 路径样（反斜杠）
        "src/Main0/Java.kt", // 路径样（斜杠）
        "550e8400-e29b-41d4-a716-446655440000", // UUID 例外
        "a1b2c3d4e5f6a7b8c9d0", // 纯十六进制只有两类
        "密码是Xk9#mQ2v哦", // 含 CJK 不算
    )

    // ---- 兜底与优先级 ----

    @Test
    fun `everything else is plain`() = assertFormat(
        ClipContentFormat.PLAIN,
        "",
        "   \r\n\t ",
        "会议纪要：本周五完成 Stage 4 端到端握手测试",
    )

    @Test
    fun `link wins over credential shaped text`() {
        // 一个 URL 同时满足口令样三类字符，但判定次序保证它是链接。
        assertEquals(ClipContentFormat.LINK, classifyClipContent("https://Ab1!x.io"))
    }

    @Test
    fun `email wins over credential shaped text`() {
        assertEquals(ClipContentFormat.EMAIL, classifyClipContent("A9._%+-b@x.io"))
    }

    @Test
    fun `bare digits win over otp context`() {
        // 裸 4–8 位数不需要词汇；词汇分支只服务带上下文的短文本。
        assertEquals(ClipContentFormat.OTP, classifyClipContent("77031"))
    }
}
