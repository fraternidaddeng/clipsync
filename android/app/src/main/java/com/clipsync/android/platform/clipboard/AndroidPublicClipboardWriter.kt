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
    override val isUsable: Boolean = true

    override fun setPrimaryClip(text: String): OsWriteStatus {
        val result = AtomicReference(OsWriteStatus.TIMEOUT)
        val done = CountDownLatch(1)
        val worker = Thread(
            {
                try {
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText(AndroidPublicClipboardWriter.CLIP_LABEL, text),
                    )
                    result.set(OsWriteStatus.SUCCESS)
                } catch (_: SecurityException) {
                    result.set(OsWriteStatus.REJECTED)
                } catch (_: RuntimeException) {
                    result.set(OsWriteStatus.REJECTED)
                } finally {
                    done.countDown()
                }
            },
            "clipsync-public-write",
        )
        worker.start()
        return if (done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            result.get()
        } else {
            OsWriteStatus.TIMEOUT
        }
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
        val result = AtomicReference(OsWriteStatus.TIMEOUT)
        val done = CountDownLatch(1)
        val worker = Thread(
            {
                try {
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
                    result.set(OsWriteStatus.SUCCESS)
                } catch (_: SecurityException) {
                    result.set(OsWriteStatus.REJECTED)
                } catch (_: RuntimeException) {
                    result.set(OsWriteStatus.REJECTED)
                } finally {
                    done.countDown()
                }
            },
            "clipsync-public-write-image",
        )
        worker.start()
        return if (done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            result.get()
        } else {
            OsWriteStatus.TIMEOUT
        }
    }
}
