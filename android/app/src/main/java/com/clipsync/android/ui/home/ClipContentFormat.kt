package com.clipsync.android.ui.home

/**
 * 本地内容形态标签（ADR 0003）。定位是检索辅助：帮人从一屏历史里凭
 * 「这是个链接 / 验证码」的印象把内容找回来，不是安全特性，不做 ML。
 *
 * 规则与 Windows 端 `ClipSync.Core/Clipboard/ClipContentClassifier.cs`
 * 逐条镜像；动任何一条启发式都必须同步两端实现、两端单测与
 * `docs/adr/0003-local-format-tags.md`。标签只在渲染时计算，不落库。
 */
enum class ClipContentFormat {
    /** 单行 http / https / ftp / www 地址。 */
    LINK,

    /** 单行邮箱地址（UI 词汇「账号」）。 */
    EMAIL,

    /** 4–8 位验证码：裸数字，或短文本含验证码词汇 + 独立数字段。 */
    OTP,

    /** 口令样单行：8–64 个可见 ASCII、无空白、至少三类字符。 */
    CREDENTIAL,

    /** 其余一切。缺省即普通文本。 */
    PLAIN,
}

private val LINK_SCHEME = Regex("^(?:https?|ftp)://\\S+$", RegexOption.IGNORE_CASE)
private val LINK_WWW = Regex("^www\\.\\S+\\.\\S+$", RegexOption.IGNORE_CASE)

// 宽松版邮箱（检索辅助，不做合法性校验）：本地部分@域名.顶级域。
private val EMAIL_ADDRESS = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

private val BARE_OTP = Regex("^\\d{4,8}$")

// 独立的 4–8 位数字段：两侧都不是数字（避免匹配长号码的一截）。
private val OTP_DIGIT_RUN = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

// UUID 不当口令看：开发场景太常见，误报打扰大于收益。
private val UUID_LIKE = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

/** 验证码上下文词汇；英文按小写比较。 */
private val OTP_KEYWORDS = listOf(
    "验证码", "校验码", "动态码", "动态密码", "取件码", "提取码", "认证码",
    "verification code", "verify code", "security code", "one-time", "otp", "2fa",
)

/** 上下文式验证码只认短信量级的短文本。 */
private const val OTP_CONTEXT_MAX_LENGTH = 120

// 独立 4 位数落在 1900–2099 更像年份，让给 PLAIN。
private const val YEAR_MIN = 1900
private const val YEAR_MAX = 2099

private const val CREDENTIAL_MIN_LENGTH = 8
private const val CREDENTIAL_MAX_LENGTH = 64

/**
 * 判定次序即优先级：LINK → EMAIL → OTP → CREDENTIAL → PLAIN。
 * 前四类都是「越具体越先认」，兜底永远是 PLAIN——宁可漏标不误标。
 */
fun classifyClipContent(content: String): ClipContentFormat {
    val text = content.trim()
    if (text.isEmpty()) return ClipContentFormat.PLAIN
    val singleLine = '\n' !in text && '\r' !in text
    if (singleLine) {
        if (LINK_SCHEME.matches(text) || LINK_WWW.matches(text)) return ClipContentFormat.LINK
        if (EMAIL_ADDRESS.matches(text)) return ClipContentFormat.EMAIL
    }
    if (isOtp(text)) return ClipContentFormat.OTP
    if (singleLine && isCredentialLike(text)) return ClipContentFormat.CREDENTIAL
    return ClipContentFormat.PLAIN
}

private fun isOtp(text: String): Boolean {
    if (BARE_OTP.matches(text)) {
        return !(text.length == 4 && text.toInt() in YEAR_MIN..YEAR_MAX)
    }
    // 短信可以多行，所以上下文分支不要求单行，只封顶长度。
    if (text.length > OTP_CONTEXT_MAX_LENGTH) return false
    val lower = text.lowercase()
    return OTP_KEYWORDS.any { it in lower } && OTP_DIGIT_RUN.containsMatchIn(text)
}

private fun isCredentialLike(text: String): Boolean {
    if (text.length !in CREDENTIAL_MIN_LENGTH..CREDENTIAL_MAX_LENGTH) return false
    // 只认可见 ASCII：内含空白或中日韩文字的都不是口令。
    if (text.any { it.code !in 0x21..0x7E }) return false
    // 路径样内容（含斜杠）与 UUID 不当口令看。
    if ('/' in text || '\\' in text) return false
    if (UUID_LIKE.matches(text)) return false
    var classes = 0
    if (text.any { it in 'a'..'z' }) classes++
    if (text.any { it in 'A'..'Z' }) classes++
    if (text.any { it in '0'..'9' }) classes++
    if (text.any { !it.isLetterOrDigit() }) classes++
    return classes >= 3
}
