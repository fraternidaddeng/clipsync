package com.clipsync.android.platform.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.clipsync.android.media.MediaLimits
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Public [ClipboardManager.setPrimaryClip] writer. Results are success, system
 * rejected, timeout, or unavailable. Clip text is never logged.
 */
class AndroidPublicClipboardWriter internal constructor(
    private val os: ClipboardWriteOs,
) : ClipboardWriter {
    constructor(
        clipboardManager: ClipboardManager,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        context: Context? = null,
    ) : this(AndroidClipboardWriteOs(clipboardManager, timeoutMillis, context))

    override fun probe(): CapabilityState =
        if (os.isUsable) CapabilityState.READY else CapabilityState.UNAVAILABLE

    override fun writeText(text: String, originEventId: String): ClipboardWriteResult {
        if (!os.isUsable) {
            return ClipboardWriteResult.Failure(ERROR_UNAVAILABLE)
        }
        return try {
            when (os.setPrimaryClip(text)) {
                OsWriteStatus.SUCCESS -> ClipboardWriteResult.Success
                OsWriteStatus.REJECTED -> ClipboardWriteResult.Failure(ERROR_REJECTED)
                OsWriteStatus.TIMEOUT -> ClipboardWriteResult.Failure(ERROR_TIMEOUT)
            }
        } catch (_: RuntimeException) {
            ClipboardWriteResult.Failure(ERROR_REJECTED)
        }
    }

    override fun writeImage(
        encoded: ByteArray,
        mimeType: String,
        originEventId: String,
    ): ClipboardWriteResult {
        if (!os.isUsable) {
            return ClipboardWriteResult.Failure(ERROR_UNAVAILABLE)
        }
        return try {
            when (os.setPrimaryImage(encoded, mimeType, originEventId)) {
                OsWriteStatus.SUCCESS -> ClipboardWriteResult.Success
                OsWriteStatus.REJECTED -> ClipboardWriteResult.Failure(ERROR_REJECTED)
                OsWriteStatus.TIMEOUT -> ClipboardWriteResult.Failure(ERROR_TIMEOUT)
            }
        } catch (_: RuntimeException) {
            ClipboardWriteResult.Failure(ERROR_REJECTED)
        }
    }

    companion object {
        const val ERROR_REJECTED = "PUBLIC_WRITE_REJECTED"
        const val ERROR_TIMEOUT = "PUBLIC_WRITE_TIMEOUT"
        const val ERROR_UNAVAILABLE = "PUBLIC_WRITE_UNAVAILABLE"
        val ERROR_CODES = setOf(ERROR_REJECTED, ERROR_TIMEOUT, ERROR_UNAVAILABLE)
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
        internal const val CLIP_LABEL = "ClipSync"
    }
}

internal interface ClipboardWriteOs {
    val isUsable: Boolean

    fun setPrimaryClip(text: String): OsWriteStatus

    fun setPrimaryImage(encoded: ByteArray, mimeType: String, originEventId: String): OsWriteStatus =
        OsWriteStatus.REJECTED
}

internal enum class OsWriteStatus {
    SUCCESS,
    REJECTED,
    TIMEOUT,
}

internal class AndroidClipboardWriteOs(
    private val clipboardManager: ClipboardManager,
    private val timeoutMillis: Long = AndroidPublicClipboardWriter.DEFAULT_TIMEOUT_MILLIS,
    private val context: Context? = null,
) : ClipboardWriteOs {
    override val isUsable: Boolean
        get() = try {
            clipboardManager.hasPrimaryClip()
            true
        } catch (_: RuntimeException) {
            false
        }

    override fun setPrimaryClip(text: String): OsWriteStatus {
        val status = runOsWrite("clipsync-public-write") {
            clipboardManager.setPrimaryClip(
                ClipData.newPlainText(AndroidPublicClipboardWriter.CLIP_LABEL, text),
            )
        }
        if (status != OsWriteStatus.SUCCESS) {
            return status
        }
        return if (primaryTextMatches(text)) OsWriteStatus.SUCCESS else OsWriteStatus.REJECTED
    }

    override fun setPrimaryImage(
        encoded: ByteArray,
        mimeType: String,
        originEventId: String,
    ): OsWriteStatus {
        val app = context?.applicationContext ?: return OsWriteStatus.REJECTED
        if (!MediaLimits.isSupportedMime(mimeType) || encoded.isEmpty()) {
            return OsWriteStatus.REJECTED
        }
        return runOsWrite("clipsync-public-write-image") {
            val shareDir = File(app.filesDir, "clipboard-share")
            shareDir.mkdirs()
            val extension = if (mimeType == MediaLimits.MIME_JPEG) "jpg" else "png"
            val file = File(shareDir, "$originEventId.$extension")
            file.writeBytes(encoded)
            val uri: Uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.clipboard",
                file,
            )
            val clip = ClipData.newUri(
                app.contentResolver,
                AndroidPublicClipboardWriter.CLIP_LABEL,
                uri,
            )
            clipboardManager.setPrimaryClip(clip)
            // Only after the clipboard actually points at the new file: a paste can no
            // longer resolve the superseded share files, so they are garbage — without
            // this, every applied image stayed in filesDir forever (and outlived the
            // retention cleanup that deletes the same pixels from history and the
            // media store). The grace window covers a paste already reading the old URI.
            ShareDirPruner.pruneSuperseded(shareDir, keep = file, nowMs = System.currentTimeMillis())
        }
    }

    /**
     * Runs [block] off this thread. On the 2s latch timeout we still join the
     * worker so a late setPrimaryClip cannot land after the caller has already
     * dropped the loop-suppression marker.
     */
    private fun runOsWrite(threadName: String, block: () -> Unit): OsWriteStatus {
        val result = AtomicReference(OsWriteStatus.TIMEOUT)
        val done = CountDownLatch(1)
        val worker = Thread(
            {
                try {
                    block()
                    result.set(OsWriteStatus.SUCCESS)
                } catch (_: SecurityException) {
                    result.set(OsWriteStatus.REJECTED)
                } catch (_: RuntimeException) {
                    result.set(OsWriteStatus.REJECTED)
                } finally {
                    done.countDown()
                }
            },
            threadName,
        )
        worker.start()
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            worker.join(timeoutMillis)
        }
        return result.get()
    }

    private fun primaryTextMatches(expected: String): Boolean {
        return try {
            val clip = clipboardManager.primaryClip ?: return true
            if (clip.itemCount < 1) {
                return true
            }
            val actual = clip.getItemAt(0).text?.toString() ?: return true
            actual == expected
        } catch (_: RuntimeException) {
            true
        }
    }
}

/**
 * Keeps the clipboard-share directory bounded: each image write creates a per-event file the
 * FileProvider serves to pasting apps, and the clipboard only ever points at the newest one.
 * Once a new write lands, the older files can never be resolved again — except by a paste
 * already in flight, which the grace window covers.
 */
internal object ShareDirPruner {
    /** How long a superseded share file survives for in-flight pastes of its URI. */
    const val GRACE_MS = 5L * 60 * 1000

    /** Deletes every file in [shareDir] other than [keep] not modified within [GRACE_MS]. */
    fun pruneSuperseded(
        shareDir: File,
        keep: File,
        nowMs: Long,
    ) {
        val cutoff = nowMs - GRACE_MS
        shareDir.listFiles()?.forEach { candidate ->
            if (candidate != keep && candidate.isFile && candidate.lastModified() <= cutoff) {
                candidate.delete()
            }
        }
    }
}
