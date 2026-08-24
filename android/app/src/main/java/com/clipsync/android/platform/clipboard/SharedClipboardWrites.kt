package com.clipsync.android.platform.clipboard

import android.content.ClipboardManager
import android.content.Context

/**
 * Process-wide [ClipboardWriteCoordinator] so every clipboard writer in the app (history copy,
 * inbound auto-apply, capability write test) and the foreground capture pipeline share one
 * suppression table: a clip this app wrote itself must never be re-captured and echoed back
 * to the paired peer.
 *
 * The privileged write fallback shares the same Shizuku UserService as background reads
 * ([RealBackgroundReaders.shizukuWriter]); only exercised after a public write failure on
 * device — Robolectric cannot validate the Binder path.
 */
object SharedClipboardWrites {
    @Volatile
    private var instance: ClipboardWriteCoordinator? = null

    fun coordinator(context: Context): ClipboardWriteCoordinator =
        instance ?: synchronized(this) {
            instance ?: buildCoordinator(context.applicationContext).also { instance = it }
        }

    private fun buildCoordinator(appContext: Context): ClipboardWriteCoordinator {
        val readers = RealBackgroundReaders.build(appContext)
        return ClipboardWriteCoordinator(
            publicWriter = AndroidPublicClipboardWriter(
                clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager,
                context = appContext,
            ),
            fallbackWriter = readers.shizukuWriter(),
        )
    }

    /** Test hook: Robolectric recreates the application per test, so drop the cached instance. */
    fun reset() {
        instance = null
    }
}
