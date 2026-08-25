package com.clipsync.android.platform.clipboard.adblog

/**
 * Versioned clipboard-change log parsers. Signatures are conservative
 * hypotheses (plan 8.3: NOT_TESTED until a device matrix run). Unknown
 * or payload-bearing lines must not match.
 */
enum class ClipboardLogRomFamily {
    AOSP,
    ONE_UI,
    MIUI_HYPEROS,
    COLOROS_ORIGINOS,
}

data class ClipboardLogMatch(
    val family: ClipboardLogRomFamily,
    val parserVersion: String,
)

interface ClipboardLogParser {
    val family: ClipboardLogRomFamily
    val version: String

    fun match(line: String): ClipboardLogMatch?
}

object ClipboardLogParsers {
    val ALL: List<ClipboardLogParser> = listOf(
        AospClipboardLogParser,
        OneUiClipboardLogParser,
        MiuiHyperOsClipboardLogParser,
        ColorOsOriginOsClipboardLogParser,
    )

    fun parserFor(family: ClipboardLogRomFamily): ClipboardLogParser =
        ALL.first { it.family == family }

    fun matchKnownChange(line: String): ClipboardLogMatch? {
        for (parser in ALL) {
            val match = parser.match(line)
            if (match != null) {
                return match
            }
        }
        return null
    }
}

internal object AospClipboardLogParser : VersionedTagParser(
    family = ClipboardLogRomFamily.AOSP,
    version = "aosp-v1",
    tags = setOf("ClipboardService"),
    messages = setOf(
        "Copied to clipboard.",
        "Copied to clipboard",
        "setPrimaryClip",
        "setPrimaryClip()",
        "notifyPrimaryClipChanged",
    ),
)

internal object OneUiClipboardLogParser : VersionedTagParser(
    family = ClipboardLogRomFamily.ONE_UI,
    version = "oneui-v1",
    tags = setOf("SemClipboardService"),
    messages = setOf(
        "setPrimaryClip",
        "notifyChanged",
        "updateClip",
        "setPrimaryClipInternal",
    ),
)

internal object MiuiHyperOsClipboardLogParser : VersionedTagParser(
    family = ClipboardLogRomFamily.MIUI_HYPEROS,
    version = "miui-hyperos-v1",
    tags = setOf("MiuiClipboardService", "MiuiClipboardManager", "HyperClipboardService"),
    messages = setOf(
        "notifyClipChanged",
        "setPrimaryClip",
        "onPrimaryClipChanged",
    ),
)

internal object ColorOsOriginOsClipboardLogParser : VersionedTagParser(
    family = ClipboardLogRomFamily.COLOROS_ORIGINOS,
    version = "coloros-originos-v1",
    tags = setOf("OplusClipboardService", "ColorClipboardService", "ClipboardServiceExtImpl"),
    messages = setOf(
        "onPrimaryClipChanged",
        "notifyPrimaryClipChanged",
        "setPrimaryClip",
    ),
)

internal abstract class VersionedTagParser(
    override val family: ClipboardLogRomFamily,
    override val version: String,
    private val tags: Set<String>,
    private val messages: Set<String>,
) : ClipboardLogParser {
    override fun match(line: String): ClipboardLogMatch? {
        val parsed = LogcatLineShape.parse(line) ?: return null
        if (parsed.tag !in tags) {
            return null
        }
        if (parsed.message !in messages) {
            return null
        }
        return ClipboardLogMatch(family, version)
    }
}

internal data class LogcatTagMessage(
    val tag: String,
    val message: String,
)

internal object LogcatLineShape {
    private val THREADTIME = Regex(
        """^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+(\S+)\s*:\s*(.*)$""",
    )
    private val BRIEF = Regex("""^[VDIWEF]/([^(]+)\(\s*\d+\):\s*(.*)$""")
    private val SLASH_TAG = Regex("""^[VDIWEF]/([^:]+):\s*(.*)$""")
    private val FILTER = Regex("""^[VDIWEF]\s+(\S+)\s*:\s*(.*)$""")
    private val BARE = Regex("""^(\S+)\s*:\s*(.*)$""")

    fun parse(line: String): LogcatTagMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        THREADTIME.matchEntire(trimmed)?.let {
            return LogcatTagMessage(it.groupValues[1], it.groupValues[2].trim())
        }
        BRIEF.matchEntire(trimmed)?.let {
            return LogcatTagMessage(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        SLASH_TAG.matchEntire(trimmed)?.let {
            return LogcatTagMessage(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        FILTER.matchEntire(trimmed)?.let {
            return LogcatTagMessage(it.groupValues[1], it.groupValues[2].trim())
        }
        BARE.matchEntire(trimmed)?.let {
            return LogcatTagMessage(it.groupValues[1], it.groupValues[2].trim())
        }
        return null
    }
}
