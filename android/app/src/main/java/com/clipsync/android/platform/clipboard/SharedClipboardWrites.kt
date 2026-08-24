package com.clipsync.android.platform.clipboard

import android.content.Context

/**
 * Process-wide [ClipboardWriteCoordinator] so every clipboard writer in the app (history copy,
 * inbound auto-apply, capability write test) and the foreground capture pipeline share one
 * suppression table: a clip this app wrote itself must never be re-captured and echoed back
 * to the paired peer.
 */
object SharedClipboardWrites {
    @Volatile
    private var instance: ClipboardWriteCoordinator? = null

    fun coordinator(context: Context): ClipboardWriteCoordinator =
        instance ?: synchronized(this) {
            instance ?: ClipboardWriteCoordinator(
                publicWriter = AndroidPublicClipboardWriter(context.applicationContext),
            ).also { instance = it }
        }

    /** Test hook: Robolectric recreates the application per test, so drop the cached instance. */
    fun reset() {
        instance = null
    }
}
