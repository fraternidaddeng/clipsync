package com.clipsync.android.share

import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.ui.settings.LocalCapturePolicy

sealed class ShareCaptureOutcome {
    data class Stored(val eventId: String) : ShareCaptureOutcome()

    data class Rejected(val reason: CaptureRejectReason) : ShareCaptureOutcome()

    data object SkippedPolicy : ShareCaptureOutcome()
}

class ShareCaptureHelper(
    private val repository: ClipRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun capture(text: String, sourceApp: String? = SOURCE_SHARE): ShareCaptureOutcome {
        if (LocalCapturePolicy.isBlocked(repository)) {
            return ShareCaptureOutcome.SkippedPolicy
        }
        val peerId = repository.getSetting(SETTING_PAIRED_PEER_ID)?.takeIf { it.isNotBlank() }
        return when (val result = repository.captureLocalText(text, sourceApp, nowMs(), peerId)) {
            is CaptureResult.Stored -> ShareCaptureOutcome.Stored(result.eventId)
            is CaptureResult.Rejected -> ShareCaptureOutcome.Rejected(result.reason)
        }
    }

    companion object {
        const val SOURCE_SHARE = "share"
    }
}
