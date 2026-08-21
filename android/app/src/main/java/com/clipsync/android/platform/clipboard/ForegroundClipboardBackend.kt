package com.clipsync.android.platform.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * Ordinary [ClipboardManager] read path used only while the process is visible.
 * Foreground-only READY means the public API is usable in the foreground; it is
 * never a claim that background clipboard read is available.
 */
class ForegroundClipboardBackend internal constructor(
    private val os: ClipboardOs,
    private val isVisible: () -> Boolean,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val imageChange: (() -> ClipboardChange?)? = null,
) : BackgroundClipboardBackend {
    constructor(
        clipboardManager: ClipboardManager,
        isVisible: () -> Boolean,
        hasher: ContentHasher = Sha256ContentHasher,
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        os = AndroidClipboardOs(clipboardManager),
        isVisible = isVisible,
        hasher = hasher,
        nowEpochMillis = nowEpochMillis,
    )

    constructor(
        context: Context,
        clipboardManager: ClipboardManager,
        isVisible: () -> Boolean,
        hasher: ContentHasher = Sha256ContentHasher,
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        os = AndroidClipboardOs(clipboardManager),
        isVisible = isVisible,
        hasher = hasher,
        nowEpochMillis = nowEpochMillis,
        imageChange = { ClipboardMediaReader.readPreferred(context, clipboardManager) },
    )

    override val mode: ClipboardReadMode = ClipboardReadMode.FOREGROUND_ONLY

    private var callback: ((ClipboardChange) -> Unit)? = null
    private var started: Boolean = false
    private var lastReadSuccessAtEpochMillis: Long? = null

    override fun probe(): CapabilityReport {
        val present = os.isServicePresent
        val visible = isVisible()
        val errorCode = when {
            !present -> ERROR_UNAVAILABLE
            !visible -> ERROR_NOT_VISIBLE
            else -> null
        }
        return CapabilityReport(
            readMode = ClipboardReadMode.FOREGROUND_ONLY,
            readState = if (present && visible) CapabilityState.READY else CapabilityState.UNAVAILABLE,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = os.systemVersion,
            lastReadSuccessAtEpochMillis = lastReadSuccessAtEpochMillis,
            errorCode = errorCode,
        )
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        callback = onChanged
        if (started) {
            return
        }
        os.addPrimaryClipChangedListener(::onPrimaryClipChanged)
        started = true
    }

    override fun stop() {
        if (!started) {
            return
        }
        os.removePrimaryClipChangedListener()
        callback = null
        started = false
    }

    override fun readText(): ClipboardReadResult {
        if (!isVisible()) {
            return ClipboardReadResult.Failure(ERROR_NOT_VISIBLE)
        }
        if (!os.isServicePresent) {
            return ClipboardReadResult.Failure(ERROR_UNAVAILABLE)
        }
        return when (val clip = os.readPrimaryText()) {
            is OsClip.Text -> {
                if (clip.value.isEmpty()) {
                    ClipboardReadResult.Empty
                } else {
                    lastReadSuccessAtEpochMillis = nowEpochMillis()
                    ClipboardReadResult.Success(clip.value)
                }
            }
            OsClip.Empty, OsClip.NonText -> ClipboardReadResult.Empty
            is OsClip.Failed -> ClipboardReadResult.Failure(clip.errorCode)
        }
    }

    override fun health(): BackendHealth {
        val checkedAt = nowEpochMillis()
        if (!started) {
            return BackendHealth(BackendHealthState.STOPPED, checkedAt)
        }
        if (!os.isServicePresent) {
            return BackendHealth(BackendHealthState.FAILED, checkedAt, ERROR_UNAVAILABLE)
        }
        if (!isVisible()) {
            return BackendHealth(BackendHealthState.DEGRADED, checkedAt, ERROR_NOT_VISIBLE)
        }
        return BackendHealth(BackendHealthState.HEALTHY, checkedAt)
    }

    private fun onPrimaryClipChanged() {
        if (!isVisible()) {
            return
        }
        val image = imageChange?.invoke()
        if (image != null) {
            lastReadSuccessAtEpochMillis = image.observedAtEpochMillis
            callback?.invoke(image)
            return
        }
        val text = (os.readPrimaryText() as? OsClip.Text)?.value
        if (text.isNullOrEmpty()) {
            return
        }
        val observedAt = nowEpochMillis()
        lastReadSuccessAtEpochMillis = observedAt
        callback?.invoke(
            ClipboardChange(
                text = text,
                contentHash = hasher.hash(text),
                observedAtEpochMillis = observedAt,
            ),
        )
    }

    companion object {
        const val ERROR_NOT_VISIBLE = "FOREGROUND_READ_NOT_VISIBLE"
        const val ERROR_UNAVAILABLE = "FOREGROUND_READ_UNAVAILABLE"
        const val ERROR_FAILED = "FOREGROUND_READ_FAILED"
    }
}

internal interface ClipboardOs {
    val systemVersion: String
    val isServicePresent: Boolean

    fun addPrimaryClipChangedListener(listener: () -> Unit)

    fun removePrimaryClipChangedListener()

    fun readPrimaryText(): OsClip

    fun readPrimaryClip(): ClipData? = null
}

internal sealed interface OsClip {
    data class Text(val value: String) : OsClip

    data object Empty : OsClip

    data object NonText : OsClip

    data class Failed(val errorCode: String) : OsClip
}

internal class AndroidClipboardOs(
    private val clipboardManager: ClipboardManager,
    override val systemVersion: String = Build.VERSION.SDK_INT.toString(),
) : ClipboardOs {
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override val isServicePresent: Boolean = true

    override fun addPrimaryClipChangedListener(listener: () -> Unit) {
        val wrapped = ClipboardManager.OnPrimaryClipChangedListener { listener() }
        this.listener = wrapped
        clipboardManager.addPrimaryClipChangedListener(wrapped)
    }

    override fun removePrimaryClipChangedListener() {
        val current = listener ?: return
        clipboardManager.removePrimaryClipChangedListener(current)
        listener = null
    }

    override fun readPrimaryText(): OsClip {
        return try {
            if (!clipboardManager.hasPrimaryClip()) {
                return OsClip.Empty
            }
            val clip = clipboardManager.primaryClip
            if (clip == null || clip.itemCount <= 0) {
                return OsClip.Empty
            }
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                return OsClip.Text(text)
            }
            val description = clip.description
            val hasTextMime = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            if (hasTextMime) OsClip.Empty else OsClip.NonText
        } catch (_: SecurityException) {
            OsClip.Failed(ForegroundClipboardBackend.ERROR_UNAVAILABLE)
        } catch (_: RuntimeException) {
            OsClip.Failed(ForegroundClipboardBackend.ERROR_FAILED)
        }
    }

    override fun readPrimaryClip(): ClipData? =
        try {
            if (!clipboardManager.hasPrimaryClip()) {
                null
            } else {
                clipboardManager.primaryClip
            }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}
