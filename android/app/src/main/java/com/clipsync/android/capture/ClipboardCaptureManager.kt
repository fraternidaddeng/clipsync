package com.clipsync.android.capture

import com.clipsync.android.platform.clipboard.BackgroundClipboardBackends
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardHealthLoop
import com.clipsync.android.ui.wizard.WizardChoices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-scoped owner of the local capture stack: backends, read coordinator,
 * capture callback, and health loop. Before Stage 6 this lived in MainActivity,
 * so swiping the Activity away silently killed background capture even while
 * the foreground service kept the process (and Shizuku binding) alive.
 *
 * Lifecycle: [ensureStarted] is idempotent and called from every main-process
 * entry point that needs capture (MainActivity, ClipboardSyncService). The
 * stack is never stopped on Activity destruction; it lives until process death
 * or a structural settings change forces a rebuild via [applyChoices].
 */
class ClipboardCaptureManager(
    private val loadChoices: () -> WizardChoices,
    private val buildStack: (WizardChoices, isVisible: () -> Boolean) -> CaptureStack,
    private val onCapture: suspend (ClipboardChange) -> Unit,
    private val scope: CoroutineScope,
    private val rebuildDebounceMs: Long = REBUILD_DEBOUNCE_MS,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * One built generation of the capture stack. [backends] is null only in
     * JVM tests, where the Android-bound assembly cannot be constructed.
     */
    class CaptureStack(
        val backends: BackgroundClipboardBackends?,
        val access: ClipboardAccessCoordinator,
        val releaseOverlayFocus: () -> Unit = {},
    )

    private val lock = Any()
    private val activityVisible = AtomicBoolean(false)
    private val rebuildEpoch = AtomicLong(0)
    private var healthJob: Job? = null

    @Volatile
    private var currentStack: CaptureStack? = null

    @Volatile
    private var appliedChoices: WizardChoices? = null

    // Guarded by [lock]: the newest requested choices and whether a debounced
    // rebuild is waiting to consume them.
    private var latestChoices: WizardChoices? = null
    private var rebuildScheduled: Boolean = false

    fun backends(): BackgroundClipboardBackends? = currentStack?.backends

    fun access(): ClipboardAccessCoordinator? = currentStack?.access

    fun isStarted(): Boolean = currentStack != null

    fun ensureStarted() {
        if (!needsMainHop()) {
            startNow()
            return
        }
        val error = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        scope.launch {
            try {
                startNow()
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("ensureStarted timed out hopping to the manager scope")
        }
        error.get()?.let { throw it }
    }

    private fun startNow() {
        synchronized(lock) {
            if (currentStack == null) {
                startLocked(loadChoices())
            }
        }
    }

    private fun needsMainHop(): Boolean {
        return try {
            val main = android.os.Looper.getMainLooper()
            main != null && main.thread.isAlive && android.os.Looper.myLooper() != main
        } catch (_: Throwable) {
            false
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            appliedChoices = null
            latestChoices = null
            rebuildScheduled = false
        }
    }

    /**
     * Tracks whether any Activity is in the resumed state. Backends built with
     * the injected `isVisible` lambda (foreground reads) observe this flag;
     * losing visibility also releases the overlay focus window, matching the
     * old Activity onStop behavior.
     */
    fun setActivityVisible(visible: Boolean) {
        val becameVisible = !activityVisible.getAndSet(visible) && visible
        if (!visible) {
            currentStack?.releaseOverlayFocus?.invoke()
            return
        }
        if (becameVisible) {
            // Cold start parks FOREGROUND_ONLY until the Activity is visible;
            // recover immediately instead of waiting for the 10s health tick.
            synchronized(lock) {
                currentStack?.access?.checkHealth()
            }
        }
    }

    /**
     * Applies new wizard choices. A read-mode-only change re-targets the live
     * coordinator; structural changes (fallback policy, overlay consent, poll
     * interval) rebuild the stack after a short debounce so slider drags do
     * not thrash Shizuku rebinds. The debounced job always applies the LATEST
     * requested choices, so a mode change arriving during the debounce window
     * is folded into the rebuild instead of retargeting the dying stack.
     */
    fun applyChoices(choices: WizardChoices) {
        synchronized(lock) {
            latestChoices = choices
            val applied = appliedChoices
            when {
                currentStack == null -> startLocked(choices)
                applied == null || structural(applied) != structural(choices) ->
                    scheduleRebuildLocked()
                // A structural rebuild is pending; it will pick up these
                // choices (including any mode change) when it fires.
                rebuildScheduled -> Unit
                else -> {
                    if (applied.preferredReadMode != choices.preferredReadMode) {
                        currentStack?.access?.requestMode(choices.preferredReadMode)
                    }
                    appliedChoices = choices
                }
            }
        }
    }

    private fun scheduleRebuildLocked() {
        rebuildScheduled = true
        val epoch = rebuildEpoch.incrementAndGet()
        scope.launch {
            if (rebuildDebounceMs > 0) {
                delay(rebuildDebounceMs)
            }
            synchronized(lock) {
                if (rebuildEpoch.get() != epoch) {
                    return@launch
                }
                rebuildScheduled = false
                val choices = latestChoices ?: return@launch
                stopLocked()
                startLocked(choices)
            }
        }
    }

    private fun startLocked(choices: WizardChoices) {
        val stack = buildStack(choices) { activityVisible.get() }
        // Assign before the health loop: the first tick is immediate and would
        // no-op (then wait 10s) if currentStack were still null.
        currentStack = stack
        appliedChoices = choices
        stack.access.requestMode(choices.preferredReadMode)
        stack.access.start { change ->
            // Persistence and policy checks run on IO even when the manager
            // scope is main-thread (overlay window operations need main).
            scope.launch(captureDispatcher) { onCapture(change) }
        }
        // Health ticks take the same lock as rebuilds: a tick must never probe
        // a stack that a concurrent applyChoices is tearing down.
        healthJob = ClipboardHealthLoop {
            currentStack?.access?.checkHealth()
        }.start(scope)
    }

    private fun stopLocked() {
        healthJob?.cancel()
        healthJob = null
        currentStack?.access?.stop()
        currentStack?.releaseOverlayFocus?.invoke()
        currentStack = null
    }

    private fun structural(choices: WizardChoices): Structural =
        Structural(
            autoFallbackAllowed = choices.autoFallbackAllowed,
            overlayConsented = choices.overlayConsented,
            pollingIntervalMs = choices.pollingIntervalMs,
        )

    private data class Structural(
        val autoFallbackAllowed: Boolean,
        val overlayConsented: Boolean,
        val pollingIntervalMs: Int,
    )

    companion object {
        const val REBUILD_DEBOUNCE_MS = 600L
    }
}
