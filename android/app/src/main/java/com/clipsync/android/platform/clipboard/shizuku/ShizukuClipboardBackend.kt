package com.clipsync.android.platform.clipboard.shizuku

import android.content.Context
import com.clipsync.android.platform.clipboard.BackendHealth
import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAuthorization
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.Sha256ContentHasher

/**
 * Shizuku event-driven clipboard backend. Listener callbacks only signal
 * "changed"; this class then reads, hashes, and forwards. After death/rebind
 * the content-hash baseline is refreshed so the rebound clip is not treated
 * as a new user copy.
 */
class ShizukuClipboardBackend internal constructor(
    private val runtime: ShizukuRuntime,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val rebindDelaysMillis: LongArray = DEFAULT_REBIND_DELAYS_MILLIS,
) : BackgroundClipboardBackend {
    constructor(context: Context) : this(AndroidShizukuRuntime(context))

    override val mode: ClipboardReadMode = ClipboardReadMode.SHIZUKU_EVENT

    private var callback: ((ClipboardChange) -> Unit)? = null
    private var started: Boolean = false
    private var session: ShizukuClipboardSession? = null
    private var baselineHash: String? = null
    private var lastErrorCode: String? = null
    private var lastReadSuccessAtEpochMillis: Long? = null
    private var rebindAttempt: Int = 0
    private var refreshingBaseline: Boolean = false

    fun fallbackWriter(): ClipboardWriter = ShizukuClipboardWriter(runtime)

    fun requestAuthorization(onResult: (granted: Boolean) -> Unit) {
        runtime.requestAuthorization(onResult)
    }

    override fun probe(): CapabilityReport {
        val blocked = diagnoseUserActionOrMismatch()
        if (blocked != null) {
            lastErrorCode = blocked
            return report(ShizukuErrorCodes.probeReadState(blocked), blocked)
        }
        return when (val bind = runtime.bindUserService()) {
            is BindResult.Failed -> {
                lastErrorCode = bind.errorCode
                report(ShizukuErrorCodes.probeReadState(bind.errorCode), bind.errorCode)
            }
            is BindResult.Bound -> {
                val ping = bind.session.pingHealth()
                if (!started) {
                    runtime.unbindUserService()
                } else {
                    session = bind.session
                }
                if (ping != null) {
                    lastErrorCode = ping
                    report(ShizukuErrorCodes.probeReadState(ping), ping)
                } else {
                    lastErrorCode = null
                    report(CapabilityState.READY, errorCode = null)
                }
            }
        }
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        callback = onChanged
        started = true
        runtime.setDeathListener(::onBinderDeath)
        runtime.setOnBound { bound ->
            if (!started) {
                return@setOnBound
            }
            attachSession(bound, refreshBaseline = true)
        }
        when (val bind = runtime.bindUserService()) {
            is BindResult.Bound -> attachSession(bind.session, refreshBaseline = true)
            is BindResult.Failed -> {
                lastErrorCode = bind.errorCode
                scheduleRebind()
            }
        }
    }

    override fun stop() {
        started = false
        runtime.cancelRebind()
        runtime.setOnBound(null)
        runtime.setDeathListener(null)
        session?.removeChangedListener()
        session = null
        callback = null
        baselineHash = null
        runtime.unbindUserService()
    }

    override fun readText(): ClipboardReadResult {
        val blocked = diagnoseUserActionOrMismatch()
        if (blocked != null) {
            lastErrorCode = blocked
            return ClipboardReadResult.Failure(blocked)
        }
        val active = session ?: when (val bind = runtime.bindUserService()) {
            is BindResult.Bound -> bind.session.also { if (started) session = it }
            is BindResult.Failed -> {
                lastErrorCode = bind.errorCode
                return ClipboardReadResult.Failure(bind.errorCode)
            }
        }
        return when (val read = active.readText()) {
            is SessionRead.Text -> {
                lastReadSuccessAtEpochMillis = nowEpochMillis()
                lastErrorCode = null
                ClipboardReadResult.Success(read.value)
            }
            SessionRead.Empty -> ClipboardReadResult.Empty
            is SessionRead.Failed -> {
                lastErrorCode = read.errorCode
                ClipboardReadResult.Failure(read.errorCode)
            }
        }
    }

    override fun health(): BackendHealth {
        val checkedAt = nowEpochMillis()
        if (!started) {
            return BackendHealth(BackendHealthState.STOPPED, checkedAt)
        }
        val blocked = diagnoseUserActionOrMismatch()
        if (blocked != null) {
            return BackendHealth(BackendHealthState.FAILED, checkedAt, blocked)
        }
        val ping = session?.pingHealth()
        if (session == null) {
            val code = lastErrorCode ?: ShizukuErrorCodes.USERSERVICE_DEAD
            return BackendHealth(degradedOrFailed(code), checkedAt, code)
        }
        if (ping != null) {
            return BackendHealth(degradedOrFailed(ping), checkedAt, ping)
        }
        return BackendHealth(BackendHealthState.HEALTHY, checkedAt)
    }

    private fun attachSession(bound: ShizukuClipboardSession, refreshBaseline: Boolean) {
        session = bound
        lastErrorCode = null
        rebindAttempt = 0
        runtime.cancelRebind()
        bound.addChangedListener(::onChangeSignal)
        if (refreshBaseline) {
            refreshHashBaseline()
        }
    }

    private fun onChangeSignal() {
        if (!started || refreshingBaseline) {
            return
        }
        when (val read = readText()) {
            is ClipboardReadResult.Success -> {
                val hash = hasher.hash(read.text)
                if (hash == baselineHash) {
                    return
                }
                baselineHash = hash
                val observedAt = nowEpochMillis()
                lastReadSuccessAtEpochMillis = observedAt
                callback?.invoke(
                    ClipboardChange(
                        text = read.text,
                        contentHash = hash,
                        observedAtEpochMillis = observedAt,
                    ),
                )
            }
            ClipboardReadResult.Empty, is ClipboardReadResult.Failure -> Unit
        }
    }

    private fun refreshHashBaseline() {
        refreshingBaseline = true
        try {
            baselineHash = when (val read = readText()) {
                is ClipboardReadResult.Success -> hasher.hash(read.text)
                else -> null
            }
        } finally {
            refreshingBaseline = false
        }
    }

    private fun onBinderDeath(kind: BinderDeathKind) {
        lastErrorCode = when (kind) {
            BinderDeathKind.SHIZUKU -> ShizukuErrorCodes.BINDER_DEAD
            BinderDeathKind.USER_SERVICE -> ShizukuErrorCodes.USERSERVICE_DEAD
            BinderDeathKind.CLIPBOARD -> ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
        }
        session?.removeChangedListener()
        session = null
        if (started) {
            scheduleRebind()
        }
    }

    private fun scheduleRebind() {
        if (!started) {
            return
        }
        val delay = rebindDelaysMillis[rebindAttempt.coerceAtMost(rebindDelaysMillis.lastIndex)]
        rebindAttempt += 1
        runtime.scheduleRebind(delay) {
            if (!started) {
                return@scheduleRebind
            }
            when (val bind = runtime.bindUserService()) {
                is BindResult.Bound -> attachSession(bind.session, refreshBaseline = true)
                is BindResult.Failed -> {
                    lastErrorCode = bind.errorCode
                    scheduleRebind()
                }
            }
        }
    }

    private fun diagnoseUserActionOrMismatch(): String? {
        if (runtime.isPreV11()) {
            return ShizukuErrorCodes.API_MISMATCH
        }
        return when (runtime.presence()) {
            ShizukuPresence.NOT_INSTALLED -> ShizukuErrorCodes.NOT_INSTALLED
            ShizukuPresence.NOT_RUNNING -> ShizukuErrorCodes.NOT_RUNNING
            ShizukuPresence.RUNNING ->
                if (runtime.isAuthorized()) null else ShizukuErrorCodes.NOT_AUTHORIZED
        }
    }

    private fun report(readState: CapabilityState, errorCode: String?): CapabilityReport {
        val presence = runtime.presence()
        return CapabilityReport(
            readMode = ClipboardReadMode.SHIZUKU_EVENT,
            readState = readState,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = runtime.systemVersion,
            authorizations = listOf(
                ClipboardAuthorization(
                    "shizuku_installed",
                    presence != ShizukuPresence.NOT_INSTALLED,
                ),
                ClipboardAuthorization(
                    "shizuku_running",
                    presence == ShizukuPresence.RUNNING,
                ),
                ClipboardAuthorization("shizuku_authorized", runtime.isAuthorized()),
            ),
            lastReadSuccessAtEpochMillis = lastReadSuccessAtEpochMillis,
            errorCode = errorCode,
        )
    }

    private fun degradedOrFailed(errorCode: String): BackendHealthState =
        if (ShizukuErrorCodes.probeReadState(errorCode) == CapabilityState.DEGRADED) {
            BackendHealthState.DEGRADED
        } else {
            BackendHealthState.FAILED
        }

    companion object {
        val DEFAULT_REBIND_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
