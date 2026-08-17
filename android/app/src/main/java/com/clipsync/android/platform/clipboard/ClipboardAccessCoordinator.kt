package com.clipsync.android.platform.clipboard

class ClipboardAccessCoordinator(
    backends: List<BackgroundClipboardBackend>,
    private val hasher: ContentHasher = Sha256ContentHasher,
    requestedReadMode: ClipboardReadMode = ClipboardReadMode.SHIZUKU_EVENT,
    autoFallbackAllowed: Boolean = true,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val capabilityStore: ClipboardCapabilityStore? = null,
    private val releaseFocusResource: () -> Unit = {},
) {
    private val backendsByMode = backends.associateBy { it.mode }
    private var listener: ((ClipboardChange) -> Unit)? = null
    private var activeBackend: BackgroundClipboardBackend? = null
    private var baselineHash: String? = null

    var modeEpoch: Long = 0L
        private set

    var lastReadState: CapabilityState = CapabilityState.UNKNOWN
        private set

    var lastSelectionReports: List<CapabilityReport> = emptyList()
        private set

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
        capabilityStore?.loadRead()?.let { saved ->
            state = state.copy(
                requestedReadMode = saved.requestedReadMode,
                autoFallbackAllowed = saved.autoFallbackAllowed,
                lastErrorCode = saved.lastErrorCode,
                lastHealthAtEpochMillis = saved.lastHealthAtEpochMillis,
            )
            modeEpoch = saved.modeEpoch
            lastReadState = saved.lastReadState
        }
    }

    fun start(onChanged: (ClipboardChange) -> Unit): ClipboardAccessState {
        listener = onChanged
        return selectAndStart(fromMode = state.requestedReadMode)
    }

    fun requestMode(mode: ClipboardReadMode): ClipboardAccessState {
        state = state.copy(requestedReadMode = mode)
        persist()
        if (listener == null) {
            return state
        }
        return selectAndStart(fromMode = mode)
    }

    fun setAutoFallbackAllowed(allowed: Boolean) {
        state = state.copy(autoFallbackAllowed = allowed)
        persist()
    }

    fun checkHealth(): ClipboardAccessState {
        val backend = activeBackend ?: return state
        val health = backend.health()
        val probe = backend.probe()
        state = state.copy(
            lastErrorCode = health.errorCode ?: probe.errorCode,
            lastHealthAtEpochMillis = health.checkedAtEpochMillis,
        )

        val needsAttention = health.state == BackendHealthState.FAILED ||
            probe.readState == CapabilityState.NEEDS_USER_ACTION
        if (needsAttention && state.autoFallbackAllowed) {
            return selectAndStart(fromMode = nextModeAfter(backend.mode))
        }
        if (needsAttention) {
            lastReadState = CapabilityState.NEEDS_USER_ACTION
            persist()
            return state
        }
        lastReadState = when (health.state) {
            BackendHealthState.HEALTHY -> CapabilityState.READY
            BackendHealthState.DEGRADED -> CapabilityState.DEGRADED
            BackendHealthState.STOPPED -> CapabilityState.UNAVAILABLE
            BackendHealthState.FAILED -> CapabilityState.NEEDS_USER_ACTION
        }
        persist()
        return state
    }

    fun stop() {
        activeBackend?.stop()
        activeBackend = null
        listener = null
        baselineHash = null
        state = state.copy(activeReadMode = null)
        persist()
    }

    private fun selectAndStart(fromMode: ClipboardReadMode?): ClipboardAccessState {
        val candidateModes = fallbackModes(fromMode)
        var lastErrorCode: String? = null
        var parkedState: CapabilityState? = null
        val reports = mutableListOf<CapabilityReport>()

        for (mode in candidateModes) {
            val backend = backendsByMode[mode] ?: continue
            val report = backend.probe()
            reports += report
            if (report.readState == CapabilityState.READY) {
                lastSelectionReports = reports
                return commitReadySwitch(backend, mode)
            }
            lastErrorCode = report.errorCode ?: "CLIPBOARD_READ_NOT_READY"
            parkedState = report.readState
            if (!state.autoFallbackAllowed) {
                break
            }
        }

        lastSelectionReports = reports
        activeBackend?.stop()
        activeBackend = null
        baselineHash = null
        lastReadState = parkedState ?: CapabilityState.UNAVAILABLE
        state = state.copy(
            activeReadMode = null,
            lastErrorCode = lastErrorCode ?: "CLIPBOARD_READ_BACKEND_MISSING",
            lastHealthAtEpochMillis = nowEpochMillis(),
        )
        persist()
        return state
    }

    private fun commitReadySwitch(
        backend: BackgroundClipboardBackend,
        mode: ClipboardReadMode,
    ): ClipboardAccessState {
        val switched = switchTo(backend)
        if (switched) {
            lastReadState = CapabilityState.READY
            state = state.copy(
                activeReadMode = mode,
                lastErrorCode = null,
                lastHealthAtEpochMillis = nowEpochMillis(),
            )
            persist()
            return state
        }
        lastReadState = if (activeBackend != null) CapabilityState.READY else CapabilityState.UNAVAILABLE
        state = state.copy(
            activeReadMode = activeBackend?.mode,
            lastErrorCode = ERROR_MODE_SWITCH_FAILED,
            lastHealthAtEpochMillis = nowEpochMillis(),
        )
        persist()
        return state
    }

    private fun switchTo(nextBackend: BackgroundClipboardBackend): Boolean {
        val previous = activeBackend
        return try {
            previous?.stop()
            releaseFocusResource()
            baselineHash = nextBackend.readText().successTextOrNull()?.let(hasher::hash)
            nextBackend.start(::handleChange)
            activeBackend = nextBackend
            modeEpoch += 1L
            true
        } catch (_: Exception) {
            runCatching { nextBackend.stop() }
            rollback(previous = previous, failed = nextBackend)
            false
        }
    }

    private fun rollback(
        previous: BackgroundClipboardBackend?,
        failed: BackgroundClipboardBackend,
    ) {
        val candidates = buildList {
            if (previous != null && previous !== failed) {
                add(previous)
            }
            val foreground = backendsByMode[ClipboardReadMode.FOREGROUND_ONLY]
            if (foreground != null && foreground !== failed && foreground !== previous) {
                add(foreground)
            }
        }
        for (target in candidates) {
            try {
                runCatching { target.stop() }
                releaseFocusResource()
                baselineHash = target.readText().successTextOrNull()?.let(hasher::hash)
                target.start(::handleChange)
                activeBackend = target
                return
            } catch (_: Exception) {
                runCatching { target.stop() }
            }
        }
        activeBackend = null
        baselineHash = null
    }

    private fun persist() {
        capabilityStore?.saveRead(
            ReadCapabilitySnapshot(
                requestedReadMode = state.requestedReadMode,
                activeReadMode = state.activeReadMode,
                autoFallbackAllowed = state.autoFallbackAllowed,
                lastErrorCode = state.lastErrorCode,
                lastHealthAtEpochMillis = state.lastHealthAtEpochMillis,
                modeEpoch = modeEpoch,
                lastReadState = lastReadState,
            ),
        )
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
        const val ERROR_MODE_SWITCH_FAILED = "CLIPBOARD_MODE_SWITCH_FAILED"

        val FALLBACK_ORDER = listOf(
            ClipboardReadMode.SHIZUKU_EVENT,
            ClipboardReadMode.ADB_LOG_OVERLAY,
            ClipboardReadMode.OVERLAY_POLLING,
            ClipboardReadMode.FOREGROUND_ONLY,
        )
    }
}
