package com.clipsync.android.platform.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The public `ClipboardManager.setPrimaryClip` writer — always the first write path (plan
 * §2.1). Its probe reports only what a real write test has verified (persisted in
 * [ClipboardCapabilityStore]); it never claims READY just because the API exists, and it is
 * never downgraded by read-mode changes.
 */
class AndroidPublicClipboardWriter(
    context: Context,
    private val capabilityStore: ClipboardCapabilityStore,
) : ClipboardWriter {
    private val clipboard =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun probe(): CapabilityState = capabilityStore.publicWriteState()

    override fun writeText(text: String, originEventId: String): ClipboardWriteResult = try {
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        ClipboardWriteResult.Success
    } catch (_: Exception) {
        ClipboardWriteResult.Failure(ERROR_WRITE_REJECTED)
    }

    companion object {
        /** The clip label carries no content; it only marks the clip as ours. */
        private const val CLIP_LABEL = "clipsync"
        const val ERROR_WRITE_REJECTED = "CLIPBOARD_WRITE_REJECTED"
    }
}
