package com.clipsync.android.capture

import com.clipsync.android.platform.clipboard.BackgroundClipboardBackends
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardHealthLoop
import com.clipsync.android.ui.wizard.WizardChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    fun backends(): BackgroundClipboardBackends? = currentStack?.backends

    fun access(): ClipboardAccessCoordinator? = currentStack?.access

    fun isStarted(): Boolean = currentStack != null

    fun ensureStarted() {
        synchronized(lock) {
            if (currentStack == null) {
                startLocked(loadChoices())
            }
        }
    }

    /**
     * Tracks whether any Activity is in the resumed state. Backends built with
     * the injected `isVisible` lambda (foreground reads) observe this flag;
     * losing visibility also releases the overlay focus window, matching the
     * old Activity onStop behavior.
     */
    fun setActivityVisible(visible: Boolean) {
        activityVisible.set(visible)
        if (!visible) {
            currentStack?.releaseOverlayFocus?.invoke()
        }
    }

    /**
     * Applies new wizard choices. A read-mode-only change re-targets the live
     * coordinator; structural changes (fallback policy, overlay consent, poll
     * interval) rebuild the stack after a short debounce so slider drags do
     * not thrash Shizuku rebinds.
     */
    fun applyChoices(choices: WizardChoices) {
        synchronized(lock) {
            if (currentStack == null) {
                startLocked(choices)
                return
            }
            val applied = appliedChoices
            if (applied == null || structural(applied) != structural(choices)) {
                scheduleRebuildLocked(choices)
                return
            }
            if (applied.preferredReadMode != choices.preferredReadMode) {
                currentStack?.access?.requestMode(choices.preferredReadMode)
            }
            appliedChoices = choices
        }
    }

    private fun scheduleRebuildLocked(choices: WizardChoices) {
        val epoch = rebuildEpoch.incrementAndGet()
        scope.launch {
            if (rebuildDebounceMs > 0) {
                delay(rebuildDebounceMs)
            }
            synchronized(lock) {
                if (rebuildEpoch.get() != epoch) {
                    return@launch
                }
                stopLocked()
                startLocked(choices)
            }
        }
    }

    private fun startLocked(choices: WizardChoices) {
        val stack = buildStack(choices) { activityVisible.get() }
        stack.access.requestMode(choices.preferredReadMode)
        stack.access.start { change ->
            scope.launch { onCapture(change) }
        }
        healthJob = ClipboardHealthLoop { stack.access.checkHealth() }.start(scope)
        currentStack = stack
        appliedChoices = choices
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
