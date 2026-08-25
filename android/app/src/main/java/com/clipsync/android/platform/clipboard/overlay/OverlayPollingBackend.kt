package com.clipsync.android.platform.clipboard.overlay

import android.os.Handler
import android.os.HandlerThread
import com.clipsync.android.platform.clipboard.BackendHealth
import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAuthorization
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.Sha256ContentHasher

/**
 * Overlay polling backend (plan 5.5). Reads through [OverlayFocusController],
 * compares content hashes, and emits only on change. [canPollNow] pauses the
 * loop when the screen is off, keyguard is locked, or the service is unhealthy.
 *
 * Overlay is a read tool only: SYSTEM_ALERT_WINDOW does not grant background
 * FGS start on Android 15+.
 */
class OverlayPollingBackend internal constructor(
    private val controller: OverlayFocusController,
    private val canPollNow: () -> Boolean,
    pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
    private val scheduler: OverlayPollScheduler,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackgroundClipboardBackend {
    constructor(
        controller: OverlayFocusController,
        canPollNow: () -> Boolean,
        pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
    ) : this(
        controller = controller,
        canPollNow = canPollNow,
        pollIntervalMillis = pollIntervalMillis,
        scheduler = HandlerOverlayPollScheduler(),
    )

    override val mode: ClipboardReadMode = ClipboardReadMode.OVERLAY_POLLING

    val pollIntervalMillis: Long = pollIntervalMillis.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)

    private var callback: ((ClipboardChange) -> Unit)? = null
    private var started: Boolean = false
    private var awaitingPermissionRestore: Boolean = false
    private var lastHash: String? = null
    private var lastReadSuccessAtEpochMillis: Long? = null

    override fun probe(): CapabilityReport {
        val permission = controller.canDrawOverlays()
        val touchableRequired = controller.requiresTouchableWindowToRead()
        val interactive = canPollNow()
        if (started) {
            if (!permission) {
                pauseForPermissionLoss()
            } else {
                resumePollingIfRestored()
            }
        }
        val (state, code) = when {
            !permission -> CapabilityState.NEEDS_USER_ACTION to ERROR_PERMISSION_MISSING
            touchableRequired -> CapabilityState.UNAVAILABLE to ERROR_TOUCHABLE_REQUIRED
            !interactive -> CapabilityState.DEGRADED to ERROR_SCREEN_NOT_INTERACTIVE
            else -> CapabilityState.READY to null
        }
        return CapabilityReport(
            readMode = ClipboardReadMode.OVERLAY_POLLING,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = controller.systemVersion,
            authorizations = listOf(ClipboardAuthorization(AUTH_OVERLAY, permission)),
            lastReadSuccessAtEpochMillis = lastReadSuccessAtEpochMillis
                ?: controller.lastReadSuccessAtEpochMillis(),
            errorCode = code,
        )
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        callback = onChanged
        started = true
        awaitingPermissionRestore = false
        refreshBaseline()
        if (started && !awaitingPermissionRestore) {
            scheduler.start(pollIntervalMillis, ::onTick)
        }
    }

    override fun stop() {
        started = false
        awaitingPermissionRestore = false
        scheduler.stop()
        callback = null
        lastHash = null
        controller.detach()
    }

    override fun readText(): ClipboardReadResult = controller.readText()

    override fun health(): BackendHealth {
        val checkedAt = nowEpochMillis()
        if (!started) {
            return BackendHealth(BackendHealthState.STOPPED, checkedAt)
        }
        if (!controller.canDrawOverlays()) {
            pauseForPermissionLoss()
            return BackendHealth(BackendHealthState.FAILED, checkedAt, ERROR_PERMISSION_MISSING)
        }
        resumePollingIfRestored()
        if (controller.requiresTouchableWindowToRead()) {
            return BackendHealth(BackendHealthState.FAILED, checkedAt, ERROR_TOUCHABLE_REQUIRED)
        }
        if (!canPollNow()) {
            return BackendHealth(BackendHealthState.DEGRADED, checkedAt, ERROR_SCREEN_NOT_INTERACTIVE)
        }
        return BackendHealth(BackendHealthState.HEALTHY, checkedAt)
    }

    private fun refreshBaseline() {
        if (!canPollNow()) {
            return
        }
        lastHash = when (val result = controller.readText()) {
            is ClipboardReadResult.Success -> {
                lastReadSuccessAtEpochMillis = nowEpochMillis()
                hasher.hash(result.text)
            }
            is ClipboardReadResult.Failure -> {
                if (result.errorCode == ERROR_PERMISSION_MISSING) {
                    pauseForPermissionLoss()
                }
                lastHash
            }
            ClipboardReadResult.Empty -> lastHash
        }
    }

    private fun onTick() {
        if (!started) {
            return
        }
        if (awaitingPermissionRestore) {
            return
        }
        if (!controller.canDrawOverlays()) {
            pauseForPermissionLoss()
            return
        }
        if (!canPollNow()) {
            controller.releaseFocus()
            return
        }
        when (val result = controller.readText()) {
            is ClipboardReadResult.Success -> {
                val hash = hasher.hash(result.text)
                val previous = lastHash
                lastHash = hash
                lastReadSuccessAtEpochMillis = nowEpochMillis()
                if (previous != null && previous != hash) {
                    callback?.invoke(
                        ClipboardChange(
                            text = result.text,
                            contentHash = hash,
                            observedAtEpochMillis = lastReadSuccessAtEpochMillis ?: nowEpochMillis(),
                            isSensitive = result.isSensitive,
                        ),
                    )
                }
            }
            ClipboardReadResult.Empty -> Unit
            is ClipboardReadResult.Failure -> {
                if (result.errorCode == ERROR_PERMISSION_MISSING) {
                    pauseForPermissionLoss()
                }
            }
        }
    }

    private fun pauseForPermissionLoss() {
        awaitingPermissionRestore = true
        scheduler.stop()
        controller.detach()
    }

    private fun resumePollingIfRestored() {
        if (!started || !awaitingPermissionRestore) {
            return
        }
        if (!controller.canDrawOverlays() || controller.requiresTouchableWindowToRead()) {
            return
        }
        awaitingPermissionRestore = false
        scheduler.start(pollIntervalMillis, ::onTick)
    }

    companion object {
        const val ERROR_PERMISSION_MISSING = OverlayFocusController.ERROR_PERMISSION_MISSING
        const val ERROR_TOUCHABLE_REQUIRED = OverlayFocusController.ERROR_TOUCHABLE_REQUIRED
        const val ERROR_READ_FAILED = OverlayFocusController.ERROR_READ_FAILED
        const val ERROR_SCREEN_NOT_INTERACTIVE = "OVERLAY_SCREEN_NOT_INTERACTIVE"

        const val MIN_POLL_INTERVAL_MS = 500L
        const val MAX_POLL_INTERVAL_MS = 2_000L
        const val DEFAULT_POLL_INTERVAL_MS = 900L

        const val AUTH_OVERLAY = "system_alert_window"
    }
}

interface OverlayPollScheduler {
    fun start(intervalMillis: Long, onTick: () -> Unit)

    fun stop()
}

internal class HandlerOverlayPollScheduler : OverlayPollScheduler {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var runnable: Runnable? = null

    override fun start(intervalMillis: Long, onTick: () -> Unit) {
        stop()
        val worker = HandlerThread("clipsync-overlay-poll").apply { start() }
        thread = worker
        val looper = worker.looper
        val workerHandler = Handler(looper)
        handler = workerHandler
        val task = object : Runnable {
            override fun run() {
                onTick()
                workerHandler.postDelayed(this, intervalMillis)
            }
        }
        runnable = task
        workerHandler.post(task)
    }

    override fun stop() {
        runnable?.let { handler?.removeCallbacks(it) }
        runnable = null
        handler = null
        thread?.quitSafely()
        thread = null
    }
}
