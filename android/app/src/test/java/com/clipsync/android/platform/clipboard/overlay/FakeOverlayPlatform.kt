package com.clipsync.android.platform.clipboard.overlay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [OverlayPlatformSeam] so JVM tests never construct WindowManager
 * views, Settings, or ClipboardManager.
 */
internal class FakeOverlayPlatform(
    override var systemVersion: String = "35",
) : OverlayPlatformSeam {
    var overlaysAllowed: Boolean = true
    var touchableRequired: Boolean = false
    var clip: OverlayClipRead = OverlayClipRead.Empty
    var throwOnRead: Boolean = false
    var throwOnAttach: Boolean = false
    var readResults: ArrayDeque<OverlayClipRead> = ArrayDeque()
    var readEntered: CountDownLatch? = null
    var blockRead: CountDownLatch? = null

    val windowHistory = mutableListOf<OverlayWindowSpec>()
    val delayCalls = mutableListOf<Long>()
    val eventLog = mutableListOf<String>()
    var readCount: Int = 0
    var detachCount: Int = 0
    private val focusableDepth = AtomicInteger(0)
    val maxFocusableDepth = AtomicInteger(0)

    @Volatile
    private var window: OverlayWindowSpec? = null

    override fun canDrawOverlays(): Boolean = overlaysAllowed

    override fun requiresTouchableWindowToRead(): Boolean = touchableRequired

    override fun attachOrUpdateWindow(spec: OverlayWindowSpec) {
        if (throwOnAttach) {
            throw RuntimeException("overlay-attach-boom")
        }
        synchronized(eventLog) {
            window = spec
            windowHistory += spec
            val focusable = spec.flags and OverlayFocusController.FLAG_NOT_FOCUSABLE == 0
            if (focusable) {
                val depth = focusableDepth.incrementAndGet()
                maxFocusableDepth.updateAndGet { current -> maxOf(current, depth) }
                eventLog += EVENT_READ_FLAGS
            } else {
                if (focusableDepth.get() > 0) {
                    focusableDepth.decrementAndGet()
                }
                eventLog += EVENT_IDLE_FLAGS
            }
        }
    }

    override fun currentWindow(): OverlayWindowSpec? = window

    override fun readPrimaryText(): OverlayClipRead {
        synchronized(eventLog) { eventLog += EVENT_READ }
        readCount += 1
        readEntered?.countDown()
        blockRead?.await(5, TimeUnit.SECONDS)
        if (throwOnRead) {
            throw RuntimeException("overlay-read-boom")
        }
        if (readResults.isNotEmpty()) {
            return readResults.removeFirst()
        }
        return clip
    }

    override fun detachWindow() {
        detachCount += 1
        window = null
        synchronized(eventLog) { eventLog += EVENT_DETACH }
    }

    override fun delay(millis: Long) {
        delayCalls += millis
    }

    fun neverDroppedTouchable(): Boolean =
        windowHistory.all { spec ->
            spec.flags and OverlayFocusController.FLAG_NOT_TOUCHABLE != 0
        }

    fun snapshotsWithoutTouchable(): List<OverlayWindowSpec> =
        windowHistory.filter { spec ->
            spec.flags and OverlayFocusController.FLAG_NOT_TOUCHABLE == 0
        }

    companion object {
        const val EVENT_IDLE_FLAGS = "idle-flags"
        const val EVENT_READ_FLAGS = "read-flags"
        const val EVENT_READ = "read"
        const val EVENT_DETACH = "detach"
    }
}
