package com.clipsync.android.notify

import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.ui.settings.SETTING_AUTO_APPLY_REMOTE
import com.clipsync.android.ui.settings.parseSettingFlag

data class InboundClip(
    val eventId: String,
    val content: String,
) {
    override fun toString(): String = "InboundClip(eventId=$eventId)"
}

class InboundClipApplier(
    private val repository: ClipRepository,
    private val writeCoordinator: ClipboardWriteCoordinator,
    private val offerManualCopy: (eventId: String) -> Unit,
) {
    suspend fun onCommitted(clips: List<InboundClip>) {
        if (clips.isEmpty()) {
            return
        }
        val autoApply = parseSettingFlag(
            repository.getSetting(SETTING_AUTO_APPLY_REMOTE),
            default = true,
        )
        for (clip in clips) {
            var writeSucceeded = false
            if (autoApply) {
                val outcome = writeCoordinator.writeText(clip.content, clip.eventId)
                writeSucceeded = outcome.result is ClipboardWriteResult.Success
            }
            if (InboundNotifyPolicy.decide(autoApply, writeSucceeded) == InboundNotifyDecision.COPY_ACTION) {
                offerManualCopy(clip.eventId)
            }
        }
    }
}
