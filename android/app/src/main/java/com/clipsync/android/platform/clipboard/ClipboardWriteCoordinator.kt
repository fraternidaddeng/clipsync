package com.clipsync.android.platform.clipboard

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
