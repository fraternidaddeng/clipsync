package com.clipsync.android.platform.clipboard

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

    var state = ClipboardAccessState(
        requestedReadMode = requestedReadMode,
        activeReadMode = null,
        autoFallbackAllowed = autoFallbackAllowed,
        lastErrorCode = null,
        lastHealthAtEpochMillis = null,
    )
        private set

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
            if (best == null || readStateRank(report.readState) < readStateRank(best.readState)) {
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
    fun probeAll(): List<CapabilityReport> =
        FALLBACK_ORDER.mapNotNull { mode -> backendsByMode[mode]?.probe() }

    /**
     * The registered backend for [mode], or null when this build has none. Used by the
     * capability wizard's device-verified read test to exercise a specific route's real
     * read path without starting or switching the active backend.
     */
    fun backend(mode: ClipboardReadMode): BackgroundClipboardBackend? = backendsByMode[mode]

    private fun readStateRank(state: CapabilityState): Int = when (state) {
        CapabilityState.READY -> 0
        CapabilityState.DEGRADED -> 1
        CapabilityState.UNKNOWN -> 2
        CapabilityState.UNAVAILABLE -> 3
    }

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
        state = state.copy(
            lastErrorCode = health.errorCode,
            lastHealthAtEpochMillis = health.checkedAtEpochMillis,
        )

        if (health.state == BackendHealthState.FAILED && state.autoFallbackAllowed) {
            return selectAndStart(fromMode = nextModeAfter(backend.mode))
        }
        return state
    }

    fun stop() {
        activeBackend?.stop()
        activeBackend = null
        listener = null
        baselineHash = null
        state = state.copy(activeReadMode = null)
    }

    private fun selectAndStart(fromMode: ClipboardReadMode?): ClipboardAccessState {
        val candidateModes = fallbackModes(fromMode)
        var lastErrorCode: String? = null

        for (mode in candidateModes) {
            val backend = backendsByMode[mode] ?: continue
            val report = backend.probe()
            if (report.readState == CapabilityState.READY) {
                switchTo(backend)
                state = state.copy(
                    activeReadMode = mode,
                    lastErrorCode = null,
                    lastHealthAtEpochMillis = nowEpochMillis(),
                )
                return state
            }
            lastErrorCode = report.errorCode ?: "CLIPBOARD_READ_NOT_READY"
            if (!state.autoFallbackAllowed) {
                break
            }
        }

        activeBackend?.stop()
        activeBackend = null
        baselineHash = null
        state = state.copy(
            activeReadMode = null,
            lastErrorCode = lastErrorCode ?: "CLIPBOARD_READ_BACKEND_MISSING",
            lastHealthAtEpochMillis = nowEpochMillis(),
        )
        return state
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

    private fun ClipboardReadResult.successTextOrNull(): String? =
        (this as? ClipboardReadResult.Success)?.text

    private companion object {
        val FALLBACK_ORDER = listOf(
            ClipboardReadMode.SHIZUKU_EVENT,
            ClipboardReadMode.ADB_LOG_OVERLAY,
            ClipboardReadMode.OVERLAY_POLLING,
            ClipboardReadMode.FOREGROUND_ONLY,
        )
    }
}
