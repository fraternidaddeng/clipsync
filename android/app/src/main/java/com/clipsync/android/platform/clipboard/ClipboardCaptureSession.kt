package com.clipsync.android.platform.clipboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which user switch keeps every read backend stopped right now, or [OPEN] when none does.
 * Ordered by the gate precedence the capture policy engine applies (暂停同步 → 私密 → 暂停捕获).
 */
enum class CaptureGate {
    OPEN,
    SYNC_PAUSED,
    PRIVATE_MODE,
    CAPTURE_PAUSED,
}

/**
 * What the session is doing right now, for the conduit and preferences pages: whether a backend
 * listens at all, who keeps the coordinator alive, and which switch (if any) holds it closed.
 */
data class CaptureSessionStatus(
    val running: Boolean,
    val owners: Set<ClipboardCaptureSession.Owner>,
    val gate: CaptureGate,
)

/**
 * Owner arbitration for the process-wide [ClipboardAccessCoordinator] (plan 5.2: the foreground
 * service holds the backend coordinator, so verified background read routes keep capturing
 * while the app itself is backgrounded).
 *
 * Two holders can keep the coordinator alive: the [Owner.FOREGROUND_SERVICE] while it is
 * promoted, and the [Owner.ACTIVITY] while it is visible. The coordinator runs as long as at
 * least one holder remains, so the ownership handoffs are seamless in both directions:
 *
 * - Service running, activity leaves the foreground → backends keep running (the stage-5
 *   acceptance path: copy on Android with the main UI backgrounded, arrive on Windows).
 * - No service, activity visible → the stage-4 foreground-only path, exactly as before.
 * - Neither holder → stopped; Android 10+ denies foreground-only reads to backgrounded apps
 *   anyway, and the privileged routes must not read with nobody accountable for them.
 *
 * [captureGate] gates the backends themselves on 暂停同步/私密模式/暂停自动捕获: while any is on,
 * no backend runs at all — a privileged reader polling the clipboard during private mode would
 * read content the user said must not be captured (and raise the OS clipboard-access toast).
 * The per-event policy engine (plan 5.6, [com.clipsync.android.sync.ClipboardCaptureManager])
 * stays authoritative on top of this; the toggle owners call [refreshGates] after flipping.
 *
 * All lifecycle callers are main-thread today; the synchronization is belt and braces for the
 * service/activity interleavings Robolectric and instrumentation drive.
 */
class ClipboardCaptureSession(
    private val coordinator: ClipboardAccessCoordinator,
    private val onChanged: (ClipboardChange) -> Unit,
    private val captureGate: () -> CaptureGate = { CaptureGate.OPEN },
) {
    enum class Owner {
        /** The promoted [sync foreground service][com.clipsync.android.sync.ClipboardSyncService]. */
        FOREGROUND_SERVICE,

        /** The visible main activity (between onStart and onStop). */
        ACTIVITY,
    }

    private val owners = linkedSetOf<Owner>()
    private var running = false

    private val mutableStatus =
        MutableStateFlow(CaptureSessionStatus(running = false, owners = emptySet(), gate = CaptureGate.OPEN))

    /** Live status for UI surfaces; updated on every ownership or gate reconciliation. */
    val status: StateFlow<CaptureSessionStatus> = mutableStatus.asStateFlow()

    /** Whether the coordinator currently runs a backend listener; drives tests and handoffs. */
    val isRunning: Boolean
        @Synchronized get() = running

    /** Whether the foreground service currently holds the session. */
    val serviceOwned: Boolean
        @Synchronized get() = Owner.FOREGROUND_SERVICE in owners

    /**
     * Idempotent: a holder that already owns the session changes nothing about who runs it. The
     * activity coming to the foreground while the service already keeps the coordinator alive is
     * also the cheapest moment to climb back to the preferred route if it recovered meanwhile
     * (the user is looking; a probe costs a few permission checks).
     */
    @Synchronized
    fun acquire(owner: Owner) {
        val wasRunning = running
        owners += owner
        reconcile()
        if (wasRunning && running && owner == Owner.ACTIVITY) {
            coordinator.tryRecover()
        }
    }

    /** Idempotent: releasing a non-held owner changes nothing (e.g. the FGS-denied teardown). */
    @Synchronized
    fun release(owner: Owner) {
        owners -= owner
        reconcile()
    }

    /**
     * Re-evaluates [captureGate] against the current holders: called after 暂停同步, 私密模式 or
     * 暂停自动捕获 flips so backends stop reading immediately (or resume on the way back).
     */
    @Synchronized
    fun refreshGates() {
        reconcile()
    }

    /**
     * Active-backend health check while running (the coordinator falls back down the ladder on
     * FAILED when the user allows it); a stopped session has nothing to check. The service
     * drives this periodically while it owns the session.
     */
    @Synchronized
    fun checkHealth() {
        if (running) {
            coordinator.checkHealth()
        }
    }

    /**
     * Upward re-probe while running (see [ClipboardAccessCoordinator.tryRecover]): the user's
     * explicit 重新探测首选路线, the service's conservative timer while degraded, and a verified
     * read test all land here. A stopped session (gate closed, no owner) never starts anything.
     */
    @Synchronized
    fun tryRecover(): Boolean {
        if (!running) {
            return false
        }
        val before = coordinator.state.activeReadMode
        return coordinator.tryRecover().activeReadMode != before
    }

    private fun reconcile() {
        val gate = captureGate()
        val shouldRun = owners.isNotEmpty() && gate == CaptureGate.OPEN
        if (shouldRun && !running) {
            // start() re-probes the ladder from the requested mode and refreshes the baseline
            // hash, so a clip present before ownership began is never announced as new.
            coordinator.start(onChanged)
            running = true
        } else if (!shouldRun && running) {
            coordinator.stop()
            running = false
        }
        mutableStatus.value = CaptureSessionStatus(running = running, owners = owners.toSet(), gate = gate)
    }
}
