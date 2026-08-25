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
    /**
     * Gate for materializing PNG/JPEG clipboard items into bytes. Re-read per change so the
     * image-sync preference applies immediately; off by default per the charter, in which
     * case image clips are ignored exactly as before.
     */
    private val imageCaptureEnabled: () -> Boolean = { false },
) : BackgroundClipboardBackend {
    private val appContext = context.applicationContext
    private val clipboard =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
            val image = readImageChange()
            if (image != null) {
                onChanged(image)
                return@OnPrimaryClipChangedListener
            }
            val read = readText() as? ClipboardReadResult.Success
                ?: return@OnPrimaryClipChangedListener
            onChanged(
                ClipboardChange(
                    read.text,
                    hasher.hash(read.text),
                    nowEpochMillis(),
                    isSensitive = read.isSensitive,
                ),
            )
        }
        clipboard.addPrimaryClipChangedListener(registered)
        listener = registered
    }

    /** A PNG/JPEG primary clip as bytes, or null when disabled, absent, or unreadable. */
    private fun readImageChange(): ClipboardChange? {
        if (!imageCaptureEnabled()) {
            return null
        }
        val clip = try {
            clipboard.primaryClip
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        if (!ClipboardMediaReader.descriptionLooksLikeImage(clip.description)) {
            return null
        }
        return ClipboardMediaReader
            .readFirstImage(appContext, clip)
            ?.copy(isSensitive = ClipSensitivity.isMarkedSensitive(clip.description))
    }

    override fun stop() {
        listener?.let(clipboard::removePrimaryClipChangedListener)
        listener = null
    }

    override fun readText(): ClipboardReadResult = try {
        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
        val text = clip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) {
            ClipboardReadResult.Empty
        } else {
            ClipboardReadResult.Success(text, ClipSensitivity.isMarkedSensitive(clip.description))
        }
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
