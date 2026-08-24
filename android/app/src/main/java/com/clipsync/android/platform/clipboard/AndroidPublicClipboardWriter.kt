package com.clipsync.android.platform.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Public-API clipboard writer (`ClipboardManager.setPrimaryClip`). AOSP allows writes without
 * focus, so this is the default write path; OEM rejections surface as stable error codes for
 * the write coordinator to decide on a privileged fallback (plan 0.1.2 rule 5).
 * The ClipData label is a constant so no content leaks through it.
 */
class AndroidPublicClipboardWriter(context: Context) : ClipboardWriter {
    private val appContext = context.applicationContext

    override fun probe(): CapabilityState {
        return if (clipboardManager() != null) CapabilityState.READY else CapabilityState.UNAVAILABLE
    }

    override fun writeText(text: String, originEventId: String): ClipboardWriteResult {
        val manager = clipboardManager()
            ?: return ClipboardWriteResult.Failure(ERROR_SERVICE_MISSING)
        return try {
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            ClipboardWriteResult.Success
        } catch (_: SecurityException) {
            ClipboardWriteResult.Failure(ERROR_WRITE_DENIED)
        } catch (_: RuntimeException) {
            ClipboardWriteResult.Failure(ERROR_WRITE_FAILED)
        }
    }

    private fun clipboardManager(): ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    companion object {
        const val ERROR_SERVICE_MISSING = "CLIPBOARD_SERVICE_MISSING"
        const val ERROR_WRITE_DENIED = "CLIPBOARD_WRITE_DENIED"
        const val ERROR_WRITE_FAILED = "CLIPBOARD_WRITE_FAILED"
        private const val CLIP_LABEL = "ClipSync"
    }
}
