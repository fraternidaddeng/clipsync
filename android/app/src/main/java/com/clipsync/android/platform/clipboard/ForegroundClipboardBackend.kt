package com.clipsync.android.platform.clipboard

import android.content.ClipboardManager
import android.content.Context

/**
 * Route 4: plain `ClipboardManager` reads while the app is visible — the lossless manual
 * baseline that stays available without any special grant (plan §2.1). Android 10+ denies
 * these reads to backgrounded apps; that is reported as a stable error code, not hidden.
 */
class ForegroundClipboardBackend(
    context: Context,
    private val systemVersion: String,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackgroundClipboardBackend {
    private val clipboard =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override val mode: ClipboardReadMode = ClipboardReadMode.FOREGROUND_ONLY

    override fun probe(): CapabilityReport = CapabilityReport(
        readMode = mode,
        readState = CapabilityState.READY,
        writeState = CapabilityState.UNKNOWN,
        systemVersion = systemVersion,
    )

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        stop()
        val registered = ClipboardManager.OnPrimaryClipChangedListener {
            val text = (readText() as? ClipboardReadResult.Success)?.text
                ?: return@OnPrimaryClipChangedListener
            onChanged(ClipboardChange(text, hasher.hash(text), nowEpochMillis()))
        }
        clipboard.addPrimaryClipChangedListener(registered)
        listener = registered
    }

    override fun stop() {
        listener?.let(clipboard::removePrimaryClipChangedListener)
        listener = null
    }

    override fun readText(): ClipboardReadResult = try {
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.text?.toString()
        if (text.isNullOrEmpty()) ClipboardReadResult.Empty else ClipboardReadResult.Success(text)
    } catch (_: SecurityException) {
        ClipboardReadResult.Failure(ERROR_ACCESS_DENIED)
    }

    /** Removes the primary clip; used to clean up generated test text right after a probe. */
    fun clear() {
        runCatching { clipboard.clearPrimaryClip() }
    }

    override fun health(): BackendHealth = BackendHealth(
        state = if (listener != null) BackendHealthState.HEALTHY else BackendHealthState.STOPPED,
        checkedAtEpochMillis = nowEpochMillis(),
    )

    companion object {
        const val ERROR_ACCESS_DENIED = "CLIPBOARD_ACCESS_DENIED"
    }
}
