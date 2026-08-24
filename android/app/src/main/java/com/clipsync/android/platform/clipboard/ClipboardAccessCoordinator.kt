package com.clipsync.android.platform.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private val gate = Any()

    @Volatile
    private var listener: ((ClipboardChange) -> Unit)? = null

    @Volatile
    private var activeBackend: BackgroundClipboardBackend? = null

    @Volatile
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
        val backend = activeBackend
        if (backend == null) {
            // Parked: nothing could start earlier (e.g. Shizuku was down at
            // process start). The stack is process-owned and never rebuilt by
            // Activity recreates, so the health loop is the only recovery path.
            return if (listener != null) {
                selectAndStart(fromMode = state.requestedReadMode)
            } else {
                state
            }
        }
        tryRecoverRequestedMode(backend)?.let { recovered ->
            return recovered
        }
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
        val backend = synchronized(gate) {
            val current = activeBackend
            activeBackend = null
            listener = null
            baselineHash = null
            state = state.copy(activeReadMode = null)
            current
        }
        backend?.stop()
        releaseFocusResource()
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
            if (report.readState == CapabilityState.READY || canStartWhileDegraded(mode, report)) {
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
        val previous = synchronized(gate) { activeBackend }
        return try {
            previous?.stop()
            releaseFocusResource()
            val nextBaseline = nextBackend.readText().successTextOrNull()?.let(hasher::hash)
            synchronized(gate) {
                baselineHash = nextBaseline
            }
            nextBackend.start(::handleChange)
            synchronized(gate) {
                activeBackend = nextBackend
                modeEpoch += 1L
            }
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
        val emit = synchronized(gate) {
            if (change.contentHash == baselineHash) {
                false
            } else {
                baselineHash = change.contentHash
                true
            }
        }
        if (emit) {
            listener?.invoke(change)
        }
    }

    /**
     * When running on a fallback backend, probe the requested (higher-ranked)
     * mode and switch back the moment it reports READY. Returns null when no
     * upgrade applies so [checkHealth] continues with the regular checks.
     */
    private fun tryRecoverRequestedMode(active: BackgroundClipboardBackend): ClipboardAccessState? {
        val requested = state.requestedReadMode
        val requestedRank = FALLBACK_ORDER.indexOf(requested)
        val activeRank = FALLBACK_ORDER.indexOf(active.mode)
        val candidate = backendsByMode[requested] ?: return null
        val canUpgrade =
            requested != active.mode &&
                requestedRank >= 0 &&
                activeRank >= 0 &&
                requestedRank < activeRank &&
                candidate.probe().readState == CapabilityState.READY
        return if (canUpgrade) {
            commitReadySwitch(candidate, requested)
        } else {
            null
        }
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

    /**
     * Shizuku [probe] can report DEGRADED while UserService bind is still
     * in flight. If the user already authorized, [start] attaches on bind.
     */
    private fun canStartWhileDegraded(
        mode: ClipboardReadMode,
        report: CapabilityReport,
    ): Boolean {
        if (mode != ClipboardReadMode.SHIZUKU_EVENT) {
            return false
        }
        if (report.readState != CapabilityState.DEGRADED) {
            return false
        }
        return report.authorizations.any { it.name == "shizuku_authorized" && it.granted }
    }

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

/**
 * Periodic [ClipboardAccessCoordinator.checkHealth] driver. Owned by the
 * process-scoped ClipboardCaptureManager for the whole process lifetime, so
 * recovery (parked retry, fallback upgrade) works without any Activity.
 */
class ClipboardHealthLoop(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val checkHealth: () -> Unit,
) {
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            checkHealth()
            while (isActive) {
                delay(intervalMillis)
                checkHealth()
            }
        }

    companion object {
        const val DEFAULT_INTERVAL_MS = 10_000L
    }
}
