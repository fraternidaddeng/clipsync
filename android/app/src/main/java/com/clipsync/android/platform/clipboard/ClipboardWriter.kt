package com.clipsync.android.platform.clipboard

interface ClipboardWriter {
    fun probe(): CapabilityState

    fun writeText(text: String, originEventId: String): ClipboardWriteResult

    fun writeImage(
        encoded: ByteArray,
        mimeType: String,
        originEventId: String,
    ): ClipboardWriteResult =
        ClipboardWriteResult.Failure(IMAGE_WRITE_UNAVAILABLE)

    companion object {
        const val IMAGE_WRITE_UNAVAILABLE = "IMAGE_WRITE_UNAVAILABLE"
    }
}

typealias PublicClipboardWriter = ClipboardWriter
