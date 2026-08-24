package com.clipsync.android.platform.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The public write path (plan.md §2.1): plain `ClipboardManager.setPrimaryClip`
 * with the actual outcome reported — a vendor rejection surfaces as a stable
 * error code instead of a faked success.
 */
class AndroidPublicClipboardWriter(context: Context) : ClipboardWriter {
    private val appContext = context.applicationContext

    override fun probe(): CapabilityState =
        if (manager() != null) CapabilityState.READY else CapabilityState.UNAVAILABLE

    override fun writeText(text: String, originEventId: String): ClipboardWriteResult {
        val manager = manager() ?: return ClipboardWriteResult.Failure(ERROR_MANAGER_MISSING)
        return try {
            // Generic label: clip labels can surface in system UI and must not
            // leak content.
            manager.setPrimaryClip(ClipData.newPlainText("clipsync", text))
            ClipboardWriteResult.Success
        } catch (_: SecurityException) {
            ClipboardWriteResult.Failure(ERROR_WRITE_DENIED)
        } catch (_: RuntimeException) {
            ClipboardWriteResult.Failure(ERROR_WRITE_FAILED)
        }
    }

    private fun manager(): ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    companion object {
        const val ERROR_MANAGER_MISSING = "public_write_manager_missing"
        const val ERROR_WRITE_DENIED = "public_write_denied"
        const val ERROR_WRITE_FAILED = "public_write_failed"
    }
}
