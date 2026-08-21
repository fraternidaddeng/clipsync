package com.clipsync.android.platform.clipboard

class ClipboardWriteCoordinator(
    private val publicWriter: PublicClipboardWriter,
    private val fallbackWriter: ClipboardWriter? = null,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val suppressionWindowMillis: Long = 5_000L,
    private val capabilityStore: ClipboardCapabilityStore? = null,
    private val fallbackWriteMode: ClipboardWriteMode = ClipboardWriteMode.SHIZUKU_FALLBACK,
) {
    private val suppressionsByOrigin = mutableMapOf<String, WriteSuppression>()
    private var publicLastSuccessAt: Long? = null
    private var publicLastError: String? = null
    private var fallbackLastSuccessAt: Long? = null
    private var fallbackLastError: String? = null

    init {
        capabilityStore?.loadWrite()?.let { saved ->
            publicLastSuccessAt = saved.publicLastSuccessAtEpochMillis
            publicLastError = saved.publicLastErrorCode
            fallbackLastSuccessAt = saved.fallbackLastSuccessAtEpochMillis
            fallbackLastError = saved.fallbackLastErrorCode
        }
    }

    val publicWriteState: CapabilityState
        get() = publicWriter.probe()

    val fallbackWriteState: CapabilityState
        get() = fallbackWriter?.probe() ?: CapabilityState.UNAVAILABLE

    fun writeMode(): ClipboardWriteMode {
        val mode = resolveWriteMode()
        persistWrite(mode)
        return mode
    }

    fun writeCapability(): WriteCapabilitySnapshot {
        val snapshot = currentWriteSnapshot(resolveWriteMode())
        persistWrite(snapshot.writeMode)
        return snapshot
    }

    fun writeImage(
        encoded: ByteArray,
        mimeType: String,
        originEventId: String,
    ): ClipboardWriteOutcome {
        require(originEventId.isNotBlank()) { "originEventId must not be blank." }
        val hash = Sha256ContentHasher.hashBytes(encoded)
        val suppression = WriteSuppression(
            contentHash = hash,
            expiresAtEpochMillis = nowEpochMillis() + suppressionWindowMillis,
        )
        synchronized(suppressionsByOrigin) {
            suppressionsByOrigin[originEventId] = suppression
        }

        val publicResult = publicWriter.writeImage(encoded, mimeType, originEventId)
        if (publicResult is ClipboardWriteResult.Success) {
            publicLastSuccessAt = nowEpochMillis()
            publicLastError = null
            persistWrite()
            return ClipboardWriteOutcome(publicResult, ClipboardWriterKind.PUBLIC_API)
        }
        publicLastError = (publicResult as ClipboardWriteResult.Failure).errorCode

        val fallback = fallbackWriter
        if (fallback != null && fallback.probe() == CapabilityState.READY) {
            val fallbackResult = fallback.writeImage(encoded, mimeType, originEventId)
            if (fallbackResult is ClipboardWriteResult.Success) {
                fallbackLastSuccessAt = nowEpochMillis()
                fallbackLastError = null
                persistWrite()
                return ClipboardWriteOutcome(fallbackResult, ClipboardWriterKind.PRIVILEGED_FALLBACK)
            }
            fallbackLastError = (fallbackResult as ClipboardWriteResult.Failure).errorCode
            persistWrite()
            clearSuppression(originEventId)
            return ClipboardWriteOutcome(fallbackResult, ClipboardWriterKind.PRIVILEGED_FALLBACK)
        }

        persistWrite()
        clearSuppression(originEventId)
        return ClipboardWriteOutcome(publicResult, ClipboardWriterKind.PUBLIC_API)
    }

    fun writeText(text: String, originEventId: String): ClipboardWriteOutcome {
        require(originEventId.isNotBlank()) { "originEventId must not be blank." }
        val suppression = WriteSuppression(
            contentHash = hasher.hash(text),
            expiresAtEpochMillis = nowEpochMillis() + suppressionWindowMillis,
        )
        synchronized(suppressionsByOrigin) {
            suppressionsByOrigin[originEventId] = suppression
        }

        val publicResult = publicWriter.writeText(text, originEventId)
        if (publicResult is ClipboardWriteResult.Success) {
            publicLastSuccessAt = nowEpochMillis()
            publicLastError = null
            persistWrite()
            return ClipboardWriteOutcome(publicResult, ClipboardWriterKind.PUBLIC_API)
        }
        publicLastError = (publicResult as ClipboardWriteResult.Failure).errorCode

        val fallback = fallbackWriter
        if (fallback != null && fallback.probe() == CapabilityState.READY) {
            val fallbackResult = fallback.writeText(text, originEventId)
            if (fallbackResult is ClipboardWriteResult.Success) {
                fallbackLastSuccessAt = nowEpochMillis()
                fallbackLastError = null
                persistWrite()
                return ClipboardWriteOutcome(fallbackResult, ClipboardWriterKind.PRIVILEGED_FALLBACK)
            }
            fallbackLastError = (fallbackResult as ClipboardWriteResult.Failure).errorCode
            persistWrite()
            clearSuppression(originEventId)
            return ClipboardWriteOutcome(fallbackResult, ClipboardWriterKind.PRIVILEGED_FALLBACK)
        }

        persistWrite()
        clearSuppression(originEventId)
        return ClipboardWriteOutcome(publicResult, ClipboardWriterKind.PUBLIC_API)
    }

    fun shouldSuppress(originEventId: String?, text: String): Boolean {
        synchronized(suppressionsByOrigin) {
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
    }

    /**
     * Capture-side loop guard. Clipboard change events carry no originEventId,
     * so a captured text matching any live suppression marker (an inbound apply,
     * a History copy, or a self-test token) is our own write echoing back, not a
     * user copy. One-shot per marker, like [shouldSuppress].
     */
    fun shouldSuppressCapture(text: String): Boolean =
        shouldSuppressCaptureHash(hasher.hash(text))

    fun shouldSuppressCapture(change: ClipboardChange): Boolean =
        if (change.imageBytes != null) {
            shouldSuppressCaptureHash(change.contentHash)
        } else {
            shouldSuppressCapture(change.text)
        }

    fun shouldSuppressCaptureHash(contentHash: String): Boolean {
        synchronized(suppressionsByOrigin) {
            purgeExpiredSuppressions()
            val entry = suppressionsByOrigin.entries.firstOrNull { it.value.contentHash == contentHash }
                ?: return false
            suppressionsByOrigin.remove(entry.key)
            return true
        }
    }

    private fun resolveWriteMode(): ClipboardWriteMode {
        if (publicWriter.probe() == CapabilityState.READY) {
            return ClipboardWriteMode.PUBLIC_API
        }
        if (fallbackWriter?.probe() == CapabilityState.READY) {
            return when (fallbackWriteMode) {
                ClipboardWriteMode.SHIZUKU_FALLBACK,
                ClipboardWriteMode.OVERLAY_FALLBACK,
                -> fallbackWriteMode
                ClipboardWriteMode.PUBLIC_API,
                ClipboardWriteMode.MANUAL_ONLY,
                -> ClipboardWriteMode.SHIZUKU_FALLBACK
            }
        }
        return ClipboardWriteMode.MANUAL_ONLY
    }

    private fun currentWriteSnapshot(mode: ClipboardWriteMode) = WriteCapabilitySnapshot(
        writeMode = mode,
        publicLastSuccessAtEpochMillis = publicLastSuccessAt,
        publicLastErrorCode = publicLastError,
        fallbackLastSuccessAtEpochMillis = fallbackLastSuccessAt,
        fallbackLastErrorCode = fallbackLastError,
    )

    private fun persistWrite(mode: ClipboardWriteMode = resolveWriteMode()) {
        capabilityStore?.saveWrite(currentWriteSnapshot(mode))
    }

    private fun clearSuppression(originEventId: String) {
        synchronized(suppressionsByOrigin) {
            suppressionsByOrigin.remove(originEventId)
        }
    }

    /** Callers must hold the [suppressionsByOrigin] monitor or be single-threaded. */
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
