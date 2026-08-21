package com.clipsync.android.share

import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.ui.settings.LocalCapturePolicy

/** Peer id must come from PairingStore, never the Room mirror. */
sealed class ShareCaptureOutcome {
    data class Stored(val eventId: String) : ShareCaptureOutcome()

    data class Rejected(val reason: CaptureRejectReason) : ShareCaptureOutcome()

    data object SkippedPolicy : ShareCaptureOutcome()
}

class ShareCaptureHelper(
    private val repository: ClipRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun capture(
        text: String,
        sourceApp: String? = SOURCE_SHARE,
        peerId: String? = null,
    ): ShareCaptureOutcome {
        if (LocalCapturePolicy.isBlocked(repository)) {
            return ShareCaptureOutcome.SkippedPolicy
        }
        val target = peerId?.takeIf { it.isNotBlank() }
        return when (val result = repository.captureLocalText(text, sourceApp, nowMs(), target)) {
            is CaptureResult.Stored -> ShareCaptureOutcome.Stored(result.eventId)
            is CaptureResult.Rejected -> ShareCaptureOutcome.Rejected(result.reason)
        }
    }

    suspend fun captureImage(
        encoded: ByteArray,
        sourceApp: String? = SOURCE_SHARE,
        peerId: String? = null,
    ): ShareCaptureOutcome {
        if (LocalCapturePolicy.isBlocked(repository)) {
            return ShareCaptureOutcome.SkippedPolicy
        }
        val target = peerId?.takeIf { it.isNotBlank() }
        return when (val result = repository.captureLocalImage(encoded, sourceApp, nowMs(), target)) {
            is CaptureResult.Stored -> ShareCaptureOutcome.Stored(result.eventId)
            is CaptureResult.Rejected -> ShareCaptureOutcome.Rejected(result.reason)
        }
    }

    companion object {
        const val SOURCE_SHARE = "share"
    }
}

sealed class SharePayload {
    data class Text(val value: String) : SharePayload()

    data class Image(val encoded: ByteArray) : SharePayload() {
        override fun equals(other: Any?): Boolean =
            other is Image && encoded.contentEquals(other.encoded)

        override fun hashCode(): Int = encoded.contentHashCode()
    }

    data object Empty : SharePayload()
}

object SharePayloadResolver {
    /**
     * Gallery share often includes both a stream and leftover text. Prefer a
     * materialized PNG/JPEG. If the share was an image and bytes failed, do
     * not fall back to a content URI or caption as text.
     */
    fun resolve(
        text: String?,
        imageBytes: ByteArray?,
        imageShare: Boolean,
    ): SharePayload {
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            return SharePayload.Image(imageBytes)
        }
        if (imageShare) {
            return SharePayload.Empty
        }
        if (!text.isNullOrEmpty()) {
            return SharePayload.Text(text)
        }
        return SharePayload.Empty
    }
}
