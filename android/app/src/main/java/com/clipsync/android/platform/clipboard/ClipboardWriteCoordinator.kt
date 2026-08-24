package com.clipsync.android.platform.clipboard

import com.clipsync.android.media.ImageCodec

class ClipboardWriteCoordinator(
    private val publicWriter: PublicClipboardWriter,
    private val fallbackWriter: ClipboardWriter? = null,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val suppressionWindowMillis: Long = 5_000L,
) {
    private val suppressionsByOrigin = mutableMapOf<String, WriteSuppression>()

    val publicWriteState: CapabilityState
        get() = publicWriter.probe()

    val fallbackWriteState: CapabilityState
        get() = fallbackWriter?.probe() ?: CapabilityState.UNAVAILABLE

    fun writeText(text: String, originEventId: String): ClipboardWriteOutcome {
        require(originEventId.isNotBlank()) { "originEventId must not be blank." }
        val suppression = WriteSuppression(
            contentHash = hasher.hash(text),
            expiresAtEpochMillis = nowEpochMillis() + suppressionWindowMillis,
        )
        suppressionsByOrigin[originEventId] = suppression

        val publicResult = publicWriter.writeText(text, originEventId)
        if (publicResult is ClipboardWriteResult.Success) {
            return ClipboardWriteOutcome(publicResult, ClipboardWriterKind.PUBLIC_API)
        }

        val fallback = fallbackWriter
        if (fallback != null && fallback.probe() == CapabilityState.READY) {
            val fallbackResult = fallback.writeText(text, originEventId)
            if (fallbackResult is ClipboardWriteResult.Success) {
                return ClipboardWriteOutcome(fallbackResult, ClipboardWriterKind.PRIVILEGED_FALLBACK)
            }
            clearSuppression(originEventId)
            return ClipboardWriteOutcome(fallbackResult, ClipboardWriterKind.PRIVILEGED_FALLBACK)
        }

        clearSuppression(originEventId)
        return ClipboardWriteOutcome(publicResult, ClipboardWriterKind.PUBLIC_API)
    }

    /**
     * Image variant of [writeText]: suppression is keyed on the encoded bytes' SHA-256 so the
     * capture pipeline (which sees the same hash via [ClipboardMediaReader]) can drop the echo.
     * Only the public writer is tried; the privileged fallback is a text-only channel.
     */
    fun writeImage(encoded: ByteArray, mimeType: String, originEventId: String): ClipboardWriteOutcome {
        require(originEventId.isNotBlank()) { "originEventId must not be blank." }
        suppressionsByOrigin[originEventId] = WriteSuppression(
            contentHash = ImageCodec.hashBytes(encoded),
            expiresAtEpochMillis = nowEpochMillis() + suppressionWindowMillis,
        )
        val result = publicWriter.writeImage(encoded, mimeType, originEventId)
        if (result !is ClipboardWriteResult.Success) {
            clearSuppression(originEventId)
        }
        return ClipboardWriteOutcome(result, ClipboardWriterKind.PUBLIC_API)
    }

    fun shouldSuppress(originEventId: String?, text: String): Boolean {
        purgeExpiredSuppressions()
        if (originEventId == null) {
            return false
        }
        val suppression = suppressionsByOrigin[originEventId] ?: return false
        val matches = suppression.contentHash == hasher.hash(text)
        if (matches) {
            suppressionsByOrigin.remove(originEventId)
        }
        return matches
    }

    /**
     * Content-only variant for capture paths that cannot know which of our own writes produced
     * the change (the platform clip-changed listener carries no origin id). Matches any
     * unexpired self-write with the same content hash and consumes it, so a clip this app just
     * wrote (history copy, inbound auto-apply, write test) is not re-captured and echoed back
     * to the peer.
     */
    fun shouldSuppressContent(text: String): Boolean = shouldSuppressContentHash(hasher.hash(text))

    /**
     * Hash-keyed variant for image captures: the clipboard listener computes the encoded
     * bytes' SHA-256 itself, so the check must not re-hash an empty text stand-in.
     */
    fun shouldSuppressContentHash(contentHash: String): Boolean {
        purgeExpiredSuppressions()
        val match = suppressionsByOrigin.entries.firstOrNull { it.value.contentHash == contentHash }
            ?: return false
        suppressionsByOrigin.remove(match.key)
        return true
    }

    private fun clearSuppression(originEventId: String) {
        suppressionsByOrigin.remove(originEventId)
    }

    private fun purgeExpiredSuppressions() {
        val now = nowEpochMillis()
        suppressionsByOrigin.entries.removeAll { (_, suppression) ->
            suppression.expiresAtEpochMillis <= now
        }
    }

    private data class WriteSuppression(
        val contentHash: String,
        val expiresAtEpochMillis: Long,
    )
}
