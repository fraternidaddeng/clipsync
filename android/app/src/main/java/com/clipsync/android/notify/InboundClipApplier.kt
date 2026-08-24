package com.clipsync.android.notify

import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteOutcome
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.storage.CLIP_KIND_IMAGE
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.SETTING_AUTO_APPLY_IMAGES
import com.clipsync.android.ui.settings.SETTING_AUTO_APPLY_REMOTE
import com.clipsync.android.ui.settings.parseSettingFlag

data class InboundClip(
    val eventId: String,
    val content: String,
    val kind: String = "text",
    val contentHash: String? = null,
    val mimeType: String? = null,
) {
    val isImage: Boolean get() = kind == CLIP_KIND_IMAGE

    override fun toString(): String = "InboundClip(eventId=$eventId, kind=$kind)"
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
        val autoApplyText = parseSettingFlag(
            repository.getSetting(SETTING_AUTO_APPLY_REMOTE),
            default = true,
        )
        val autoApplyImages = parseSettingFlag(
            repository.getSetting(SETTING_AUTO_APPLY_IMAGES),
            default = false,
        )
        val lastText = clips.lastOrNull { !it.isImage }
        val lastImage = clips.lastOrNull { it.isImage }
        for (clip in clips) {
            var writeSucceeded = false
            val autoApply = if (clip.isImage) autoApplyImages else autoApplyText
            val applyTarget = clip === lastText || clip === lastImage
            if (autoApply && applyTarget) {
                val outcome = if (clip.isImage) {
                    applyImage(clip)
                } else {
                    writeCoordinator.writeText(clip.content, clip.eventId)
                }
                writeSucceeded = outcome.result is ClipboardWriteResult.Success
            }
            if (InboundNotifyPolicy.decide(autoApply, writeSucceeded) == InboundNotifyDecision.COPY_ACTION) {
                offerManualCopy(clip.eventId)
            }
        }
    }

    private suspend fun applyImage(clip: InboundClip): ClipboardWriteOutcome {
        val entry = repository.findVisibleEntry(clip.eventId)
        val hash = entry?.contentHash ?: clip.contentHash
        val mime = entry?.mimeType ?: clip.mimeType
        if (hash == null || mime == null) {
            return ClipboardWriteOutcome(
                ClipboardWriteResult.Failure(
                    com.clipsync.android.platform.clipboard.ClipboardWriter.IMAGE_WRITE_UNAVAILABLE,
                ),
                writerKind = null,
            )
        }
        val bytes = runCatching { repository.media.readAllBytes(hash) }.getOrNull()
            ?: return ClipboardWriteOutcome(
                ClipboardWriteResult.Failure(
                    com.clipsync.android.platform.clipboard.ClipboardWriter.IMAGE_WRITE_UNAVAILABLE,
                ),
                writerKind = null,
            )
        return writeCoordinator.writeImage(bytes, mime, clip.eventId)
    }
}
