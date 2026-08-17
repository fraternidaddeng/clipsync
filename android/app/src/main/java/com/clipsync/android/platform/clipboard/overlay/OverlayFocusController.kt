package com.clipsync.android.platform.clipboard.overlay

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.clipsync.android.platform.clipboard.ClipboardReadResult

/**
 * Serializes every overlay clipboard read. The window stays 1x1 / alpha 0 with
 * [FLAG_NOT_TOUCHABLE] at all times; a read removes only [FLAG_NOT_FOCUSABLE]
 * for up to [MAX_READ_TRIES] attempts, then restores it immediately — including
 * when the platform seam throws.
 *
 * WindowManager / Settings.canDrawOverlays / ClipboardManager are reached only
 * through [OverlayPlatformSeam] so JVM tests never build real views.
 */
class OverlayFocusController internal constructor(
    private val platform: OverlayPlatformSeam,
    retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(AndroidOverlayPlatform(context))

    private val lock = Any()
    private val retryDelayMillis: Long =
        retryDelayMillis.coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastSuccessAt: Long? = null

    val systemVersion: String
        get() = platform.systemVersion

    fun canDrawOverlays(): Boolean = platform.canDrawOverlays()

    fun requiresTouchableWindowToRead(): Boolean = platform.requiresTouchableWindowToRead()

    fun lastErrorCode(): String? = lastError

    fun lastReadSuccessAtEpochMillis(): Long? = lastSuccessAt

    fun readText(): ClipboardReadResult = synchronized(lock) { doReadText() }

    /**
     * Restore idle flags (not-focusable + not-touchable). Used when polling
     * pauses. Does not remove the window; call [detach] for that.
     */
    fun releaseFocus() {
        synchronized(lock) {
            if (platform.currentWindow() == null) {
                return
            }
            applyWindow(idleSpec())
        }
    }

    /**
     * Remove the overlay window. Idempotent and serialized on the same lock
     * as [readText] / [releaseFocus].
     */
    fun detach() {
        synchronized(lock) {
            platform.detachWindow()
        }
    }

    private fun doReadText(): ClipboardReadResult {
        if (!platform.canDrawOverlays()) {
            lastError = ERROR_PERMISSION_MISSING
            platform.detachWindow()
            return ClipboardReadResult.Failure(ERROR_PERMISSION_MISSING)
        }
        if (platform.requiresTouchableWindowToRead()) {
            lastError = ERROR_TOUCHABLE_REQUIRED
            return ClipboardReadResult.Failure(ERROR_TOUCHABLE_REQUIRED)
        }
        applyWindow(idleSpec())
        try {
            applyWindow(readSpec())
            return readWithRetries()
        } catch (_: RuntimeException) {
            lastError = ERROR_READ_FAILED
            return ClipboardReadResult.Failure(ERROR_READ_FAILED)
        } finally {
            applyWindow(idleSpec())
        }
    }

    private fun readWithRetries(): ClipboardReadResult {
        var lastFailure: ClipboardReadResult? = null
        repeat(MAX_READ_TRIES) { attempt ->
            when (val clip = platform.readPrimaryText()) {
                is OverlayClipRead.Text -> {
                    if (clip.value.isNotEmpty()) {
                        lastError = null
                        lastSuccessAt = nowEpochMillis()
                        return ClipboardReadResult.Success(clip.value)
                    }
                    lastFailure = ClipboardReadResult.Empty
                }
                OverlayClipRead.Empty, OverlayClipRead.NonText -> {
                    lastFailure = ClipboardReadResult.Empty
                }
                is OverlayClipRead.Failed -> {
                    lastFailure = ClipboardReadResult.Failure(clip.errorCode)
                }
            }
            if (attempt < MAX_READ_TRIES - 1) {
                platform.delay(retryDelayMillis)
            }
        }
        lastError = (lastFailure as? ClipboardReadResult.Failure)?.errorCode
        return lastFailure ?: ClipboardReadResult.Empty
    }

    private fun applyWindow(spec: OverlayWindowSpec) {
        require(spec.flags and FLAG_NOT_TOUCHABLE != 0) {
            "FLAG_NOT_TOUCHABLE must stay set"
        }
        platform.attachOrUpdateWindow(spec)
    }

    private fun idleSpec(): OverlayWindowSpec = OverlayWindowSpec(
        widthPx = WINDOW_WIDTH_PX,
        heightPx = WINDOW_HEIGHT_PX,
        alpha = WINDOW_ALPHA,
        flags = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE,
        type = TYPE_APPLICATION_OVERLAY,
    )

    private fun readSpec(): OverlayWindowSpec = OverlayWindowSpec(
        widthPx = WINDOW_WIDTH_PX,
        heightPx = WINDOW_HEIGHT_PX,
        alpha = WINDOW_ALPHA,
        flags = FLAG_NOT_TOUCHABLE,
        type = TYPE_APPLICATION_OVERLAY,
    )

    companion object {
        const val ERROR_PERMISSION_MISSING = "OVERLAY_PERMISSION_MISSING"
        const val ERROR_TOUCHABLE_REQUIRED = "OVERLAY_TOUCHABLE_REQUIRED"
        const val ERROR_READ_FAILED = "OVERLAY_READ_FAILED"

        const val MAX_READ_TRIES = 3
        const val MIN_RETRY_DELAY_MS = 25L
        const val MAX_RETRY_DELAY_MS = 50L
        const val DEFAULT_RETRY_DELAY_MS = 35L

        const val WINDOW_WIDTH_PX = 1
        const val WINDOW_HEIGHT_PX = 1
        const val WINDOW_ALPHA = 0f

        const val FLAG_NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        const val FLAG_NOT_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        const val TYPE_APPLICATION_OVERLAY = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }
}

/**
 * Injectable WindowManager / Settings.canDrawOverlays / ClipboardManager seam.
 * JVM tests supply a fake; production uses [AndroidOverlayPlatform].
 */
interface OverlayPlatformSeam {
    val systemVersion: String

    fun canDrawOverlays(): Boolean

    /**
     * When true, this ROM only yields clipboard text if the overlay is
     * touchable. The controller must return UNAVAILABLE and never drop
     * [OverlayFocusController.FLAG_NOT_TOUCHABLE].
     */
    fun requiresTouchableWindowToRead(): Boolean

    fun attachOrUpdateWindow(spec: OverlayWindowSpec)

    fun currentWindow(): OverlayWindowSpec?

    fun readPrimaryText(): OverlayClipRead

    fun detachWindow()

    fun delay(millis: Long)
}

data class OverlayWindowSpec(
    val widthPx: Int,
    val heightPx: Int,
    val alpha: Float,
    val flags: Int,
    val type: Int,
)

sealed interface OverlayClipRead {
    data class Text(val value: String) : OverlayClipRead

    data object Empty : OverlayClipRead

    data object NonText : OverlayClipRead

    data class Failed(val errorCode: String) : OverlayClipRead
}

internal class AndroidOverlayPlatform(
    private val context: Context,
) : OverlayPlatformSeam {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    override val systemVersion: String = Build.VERSION.SDK_INT.toString()

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun requiresTouchableWindowToRead(): Boolean = false

    override fun attachOrUpdateWindow(spec: OverlayWindowSpec) {
        val manager = windowManager ?: return
        val layout = params ?: WindowManager.LayoutParams().also { fresh ->
            fresh.gravity = Gravity.TOP or Gravity.START
            fresh.format = PixelFormat.TRANSLUCENT
            params = fresh
        }
        layout.width = spec.widthPx
        layout.height = spec.heightPx
        layout.alpha = spec.alpha
        layout.flags = spec.flags
        layout.type = spec.type
        val existing = view
        if (existing == null) {
            val overlay = View(context)
            overlay.alpha = 0f
            manager.addView(overlay, layout)
            view = overlay
        } else {
            manager.updateViewLayout(existing, layout)
        }
    }

    override fun currentWindow(): OverlayWindowSpec? {
        val layout = params ?: return null
        if (view == null) {
            return null
        }
        return OverlayWindowSpec(
            widthPx = layout.width,
            heightPx = layout.height,
            alpha = layout.alpha,
            flags = layout.flags,
            type = layout.type,
        )
    }

    override fun readPrimaryText(): OverlayClipRead {
        val clipboard = clipboardManager
            ?: return OverlayClipRead.Failed(OverlayFocusController.ERROR_READ_FAILED)
        return try {
            if (!clipboard.hasPrimaryClip()) {
                return OverlayClipRead.Empty
            }
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount <= 0) {
                return OverlayClipRead.Empty
            }
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                return OverlayClipRead.Text(text)
            }
            val description = clip.description
            val hasTextMime = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            if (hasTextMime) OverlayClipRead.Empty else OverlayClipRead.NonText
        } catch (_: SecurityException) {
            OverlayClipRead.Failed(OverlayFocusController.ERROR_READ_FAILED)
        } catch (_: RuntimeException) {
            OverlayClipRead.Failed(OverlayFocusController.ERROR_READ_FAILED)
        }
    }

    override fun detachWindow() {
        val overlay = view ?: return
        try {
            windowManager?.removeView(overlay)
        } catch (_: RuntimeException) {
            // Window already gone; still drop the local handle.
        }
        view = null
        params = null
    }

    override fun delay(millis: Long) {
        Thread.sleep(millis)
    }
}
