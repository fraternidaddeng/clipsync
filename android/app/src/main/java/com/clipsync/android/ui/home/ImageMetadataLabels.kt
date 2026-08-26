package com.clipsync.android.ui.home

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Machine-voice labels for image clip metadata, the exact mirror of Windows
 * `ClipSync.App.Ui.ImageMetadata` (骨共享): the thumbnail is the content hero,
 * so encoding / dimensions / byte size render as quiet mono annotation pills
 * (tokens.md §6 机器的声音), never as a headline. Unknown facts yield an empty
 * label — the pill hides instead of showing a "?" placeholder. Locale-neutral
 * by construction: digits, binary units and the mime subtype need no strings.
 */
object ImageMetadataLabels {
    private const val KIB = 1024L
    private const val MIB = KIB * KIB

    /** "image/png" → "PNG"; unknown or blank mime yields no label at all. */
    fun format(mimeType: String?): String {
        if (mimeType.isNullOrBlank()) {
            return ""
        }
        return mimeType.substringAfterLast('/').trim().uppercase()
    }

    /** "320×200"; empty when either dimension is unknown (no pill, not "?×?"). */
    fun dimensions(
        pixelWidth: Int?,
        pixelHeight: Int?,
    ): String = if (pixelWidth == null || pixelHeight == null) "" else "$pixelWidth×$pixelHeight"

    /** "96 B" / "2 KiB" / "1.5 MiB" — binary units matching the 16 MiB wire cap. */
    fun byteSize(encodedBytes: Int?): String {
        if (encodedBytes == null) {
            return ""
        }
        return when {
            encodedBytes < KIB -> "$encodedBytes B"
            encodedBytes < MIB -> "${trimmed(encodedBytes / KIB.toDouble())} KiB"
            else -> "${trimmed(encodedBytes / MIB.toDouble())} MiB"
        }
    }

    /** One decimal at most, trailing zero dropped ("2", "2.3") — the Windows "0.#" form. */
    private fun trimmed(value: Double): String {
        val rounded = BigDecimal(value).setScale(1, RoundingMode.HALF_UP)
        return rounded.stripTrailingZeros().toPlainString()
    }
}
