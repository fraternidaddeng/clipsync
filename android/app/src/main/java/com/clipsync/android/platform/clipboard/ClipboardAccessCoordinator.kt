package com.clipsync.android.platform.clipboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ClipboardAccessCoordinator(
    backends: List<BackgroundClipboardBackend>,
    private val hasher: ContentHasher = Sha256ContentHasher,
    requestedReadMode: ClipboardReadMode = ClipboardReadMode.SHIZUKU_EVENT,
    autoFallbackAllowed: Boolean = true,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val backendsByMode = backends.associateBy { it.mode }
    private var listener: ((ClipboardChange) -> Unit)? = null
    private var activeBackend: BackgroundClipboardBackend? = null
    private var baselineHash: String? = null

    private val mutableStates =
        MutableStateFlow(
            ClipboardAccessState(
                requestedReadMode = requestedReadMode,
                activeReadMode = null,
                autoFallbackAllowed = autoFallbackAllowed,
                lastErrorCode = null,
                lastHealthAtEpochMillis = null,
            ),
        )

    var state: ClipboardAccessState
        get() = mutableStates.value
        private set(value) {
            mutableStates.value = value
        }

    /**
     * Live mirror of [state] for UI surfaces: the conduit page must show the route that actually
     * runs — including a fallback the service's health tick performed while the page was open —
     * not the one the last manual probe pass would have picked.
     */
    val states: StateFlow<ClipboardAccessState> = mutableStates.asStateFlow()

    /**
     * Invoked on the caller's thread whenever the active read mode actually changes —
     * a start, an explicit mode request, a health-check fallback, a recovery, or a stop. The
     * resident sync notification states the overlay-polling route honestly (plan 5.5),
     * so it must hear the switch when it happens, not on the next periodic tick.
     */
    var onActiveReadModeChanged: ((ClipboardReadMode?) -> Unit)? = null

    init {
        require(backendsByMode.size == backends.size) { "Clipboard backend modes must be unique." }
    }

    fun start(onChanged: (ClipboardChange) -> Unit): ClipboardAccessState {
        listener = onChanged
        return selectAndStart(fromMode = state.requestedReadMode)
    }

    /**
     * Probes every registered backend in fallback order without starting any of
     * them and returns the most capable report (READY > DEGRADED > UNKNOWN >
     * UNAVAILABLE), or null when no backends are registered. Health UI uses
     * this; it never changes which backend is active.
     */
    fun probe(): CapabilityReport? {
        var best: CapabilityReport? = null
        for (mode in FALLBACK_ORDER) {
            val backend = backendsByMode[mode] ?: continue
            val report = backend.probe()
            if (best == null || rank(report.readState) < rank(best.readState)) {
                best = report
            }
            if (report.readState == CapabilityState.READY) {
                break
            }
        }
        return best
    }

    /**
     * Probes every registered backend in capability-ladder order without starting, stopping or
     * switching anything. Drives the conduit page and the capability wizard; safe to call
     * repeatedly (on resume and on user refresh).
     */
    fun probeAll(): List<CapabilityReport> = FALLBACK_ORDER.mapNotNull { mode -> backendsByMode[mode]?.probe() }

    /**
     * The registered backend for [mode], or null when this build has none. Used by the
     * capability wizard's device-verified read test to exercise a specific route's real
     * read path without starting or switching the active backend.
     */
    fun backend(mode: ClipboardReadMode): BackgroundClipboardBackend? = backendsByMode[mode]

    fun requestMode(mode: ClipboardReadMode): ClipboardAccessState {
        state = state.copy(requestedReadMode = mode)
        if (listener == null) {
            return state
        }
        return selectAndStart(fromMode = mode)
    }

    fun setAutoFallbackAllowed(allowed: Boolean) {
        state = state.copy(autoFallbackAllowed = allowed)
    }

    fun checkHealth(): ClipboardAccessState {
        val backend = activeBackend ?: return state
        val health = backend.health()
        state =
            state.copy(
                lastErrorCode = health.errorCode,
                lastHealthAtEpochMillis = health.checkedAtEpochMillis,
            )

        if (health.state == BackendHealthState.FAILED && state.autoFallbackAllowed) {
            return selectAndStart(
                fromMode = nextModeAfter(backend.mode),
                fallbackFrom = backend.mode,
                fallbackErrorCode = health.errorCode,
            )
        }
        return state
    }

    /**
     * Climbs back up the ladder when possible. While a backend runs below the requested rung
     * (a start that found the preferred route not READY, or a health-check fallback), the rungs
     * from the requested one down to — but excluding — the running one are probed in order;
     * the first READY backend takes over through the same [switchTo] sequence a fallback uses
     * (stop old, refresh baseline, start new), so exactly one backend listens at any moment and
     * the clip already on the clipboard is never re-announced. With nothing running yet (no
     * rung was READY at start) the full selection from the requested rung runs again.
     *
     * A no-op when the coordinator is stopped, already at the requested rung, or nothing higher
     * is READY — probing alone never starts, stops or switches a backend, so callers may invoke
     * this on every foreground return and on a conservative timer.
     */
    fun tryRecover(): ClipboardAccessState {
        val current = activeBackend
        return when {
            listener == null -> state
            current == null -> selectAndStart(fromMode = state.requestedReadMode)
            else -> recoverAbove(current.mode)
        }
    }

    /** Probes the rungs above [currentMode] down to it and switches to the first READY one. */
    private fun recoverAbove(currentMode: ClipboardReadMode): ClipboardAccessState {
        val requestedIndex = FALLBACK_ORDER.indexOf(state.requestedReadMode)
        val currentIndex = FALLBACK_ORDER.indexOf(currentMode)
        if (requestedIndex < 0 || currentIndex <= requestedIndex) {
            return state
        }
        var preferredErrorCode: String? = null
        for (mode in FALLBACK_ORDER.subList(requestedIndex, currentIndex)) {
            val backend = backendsByMode[mode] ?: continue
            val report = backend.probe()
            if (report.readState == CapabilityState.READY) {
                val now = nowEpochMillis()
                switchTo(backend)
                publishState(
                    state.copy(
                        activeReadMode = mode,
                        lastErrorCode = null,
                        lastHealthAtEpochMillis = now,
                        shortfall =
                            shortfallFor(
                                active = mode,
                                requested = state.requestedReadMode,
                                errorCode = preferredErrorCode,
                                at = now,
                            ),
                        lastRecoveryAtEpochMillis = now,
                    ),
                )
                break
            }
            if (mode == state.requestedReadMode) {
                preferredErrorCode = report.errorCode ?: "CLIPBOARD_READ_NOT_READY"
            }
        }
        return state
    }

    fun stop() {
        activeBackend?.stop()
        activeBackend = null
        listener = null
        baselineHash = null
        publishState(state.copy(activeReadMode = null, shortfall = null))
    }

    private fun selectAndStart(
        fromMode: ClipboardReadMode?,
        fallbackFrom: ClipboardReadMode? = null,
        fallbackErrorCode: String? = null,
    ): ClipboardAccessState {
        val candidateModes = fallbackModes(fromMode)
        var lastErrorCode: String? = null
        var preferredErrorCode: String? = null

        for (mode in candidateModes) {
            val backend = backendsByMode[mode] ?: continue
            val report = backend.probe()
            if (report.readState == CapabilityState.READY) {
                val now = nowEpochMillis()
                switchTo(backend)
                publishState(
                    state.copy(
                        activeReadMode = mode,
                        lastErrorCode = null,
                        lastHealthAtEpochMillis = now,
                        shortfall =
                            if (fallbackFrom != null) {
                                ReadRouteShortfall(
                                    cause = ReadRouteShortfallCause.HEALTH_FALLBACK,
                                    fromMode = fallbackFrom,
                                    errorCode = fallbackErrorCode,
                                    sinceEpochMillis = now,
                                )
                            } else {
                                shortfallFor(
                                    active = mode,
                                    requested = state.requestedReadMode,
                                    errorCode = preferredErrorCode,
                                    at = now,
                                )
                            },
                    ),
                )
                return state
            }
            lastErrorCode = report.errorCode ?: "CLIPBOARD_READ_NOT_READY"
            if (mode == state.requestedReadMode) {
                preferredErrorCode = lastErrorCode
            }
            if (!state.autoFallbackAllowed) {
                break
            }
        }

        activeBackend?.stop()
        activeBackend = null
        baselineHash = null
        publishState(
            state.copy(
                activeReadMode = null,
                lastErrorCode = lastErrorCode ?: "CLIPBOARD_READ_BACKEND_MISSING",
                lastHealthAtEpochMillis = nowEpochMillis(),
                shortfall = null,
            ),
        )
        return state
    }

    /** Null when [active] is the requested rung; otherwise the preferred-not-ready shortfall. */
    private fun shortfallFor(
        active: ClipboardReadMode,
        requested: ClipboardReadMode,
        errorCode: String?,
        at: Long,
    ): ReadRouteShortfall? =
        if (active == requested) {
            null
        } else {
            ReadRouteShortfall(
                cause = ReadRouteShortfallCause.PREFERRED_NOT_READY,
                fromMode = requested,
                errorCode = errorCode,
                sinceEpochMillis = at,
            )
        }

    /** Applies [next] and tells the mode listener when the active route changed. */
    private fun publishState(next: ClipboardAccessState) {
        val previousMode = state.activeReadMode
        state = next
        if (previousMode != next.activeReadMode) {
            onActiveReadModeChanged?.invoke(next.activeReadMode)
        }
    }

    private fun switchTo(nextBackend: BackgroundClipboardBackend) {
        activeBackend?.stop()
        activeBackend = nextBackend
        baselineHash = nextBackend.readText().successTextOrNull()?.let(hasher::hash)
        nextBackend.start(::handleChange)
    }

    private fun handleChange(change: ClipboardChange) {
        if (change.contentHash == baselineHash) {
            baselineHash = null
            return
        }
        baselineHash = change.contentHash
        listener?.invoke(change)
    }

    private fun fallbackModes(fromMode: ClipboardReadMode?): List<ClipboardReadMode> {
        val mode = fromMode ?: return emptyList()
        val index = FALLBACK_ORDER.indexOf(mode)
        return if (index < 0) emptyList() else FALLBACK_ORDER.drop(index)
    }

    private fun nextModeAfter(mode: ClipboardReadMode): ClipboardReadMode? {
        val index = FALLBACK_ORDER.indexOf(mode)
        return FALLBACK_ORDER.getOrNull(index + 1)
    }

    private fun ClipboardReadResult.successTextOrNull(): String? = (this as? ClipboardReadResult.Success)?.text

    companion object {
        private val FALLBACK_ORDER =
            listOf(
                ClipboardReadMode.SHIZUKU_EVENT,
                ClipboardReadMode.ADB_LOG_OVERLAY,
                ClipboardReadMode.OVERLAY_POLLING,
                ClipboardReadMode.FOREGROUND_ONLY,
            )

        /** Most capable first; the tail states all mean "nothing usable observed yet". */
        private val READ_STATE_ORDER =
            listOf(
                CapabilityState.READY,
                CapabilityState.DEGRADED,
                CapabilityState.NEEDS_USER_ACTION,
                CapabilityState.UNKNOWN,
                CapabilityState.UNAVAILABLE,
            )

        /**
         * The most capable of [reports] under the same ordering [probe] uses (READY >
         * DEGRADED > NEEDS_USER_ACTION > UNKNOWN > UNAVAILABLE); ties keep the earlier
         * entry, i.e. the higher ladder rung. Lets a caller that already holds a full
         * [probeAll] pass derive the headline report without probing every backend again.
         */
        fun mostCapable(reports: List<CapabilityReport>): CapabilityReport? = reports.minByOrNull { rank(it.readState) }

        /** Ladder position of [mode]; lower is more capable. Exposed for status mapping. */
        fun ladderIndex(mode: ClipboardReadMode): Int = FALLBACK_ORDER.indexOf(mode)

        private fun rank(state: CapabilityState): Int = READ_STATE_ORDER.indexOf(state)
    }
}
