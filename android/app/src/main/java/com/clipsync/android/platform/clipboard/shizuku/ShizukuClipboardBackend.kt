package com.clipsync.android.platform.clipboard.shizuku

import android.content.Context
import android.util.Log
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
 * 特权直读 event-driven clipboard backend. Listener callbacks only signal
 * "changed"; this class then reads, hashes, and forwards. After death/rebind
 * the content-hash baseline is refreshed so the rebound clip is not treated
 * as a new user copy.
 */
class ShizukuClipboardBackend internal constructor(
    private val runtime: ShizukuRuntime,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val rebindDelaysMillis: LongArray = DEFAULT_REBIND_DELAYS_MILLIS,
    private val verifyBind: VerifyBindBudget = VerifyBindBudget.DEFAULT,
    private val logger: (String) -> Unit = DEFAULT_LOGGER,
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
        if (!started) {
            val existing = runtime.currentSession()
            if (existing != null) {
                return reportPing(existing.pingHealth())
            }
            lastErrorCode = null
            return report(CapabilityState.READY, errorCode = null)
        }
        return when (val bind = runtime.bindUserService()) {
            is BindResult.Failed -> {
                lastErrorCode = bind.errorCode
                report(ShizukuErrorCodes.probeReadState(bind.errorCode), bind.errorCode)
            }
            BindResult.Binding -> {
                report(CapabilityState.DEGRADED, errorCode = null)
            }
            is BindResult.Bound -> {
                val ping = bind.session.pingHealth()
                // After UserService restart the probe can win the race against
                // onBound; attachSession re-registers the change listener.
                val attached = attachSession(
                    bind.session,
                    refreshBaseline = session !== bind.session,
                )
                if (!attached) {
                    val code = lastErrorCode ?: ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
                    scheduleRebind()
                    report(ShizukuErrorCodes.probeReadState(code), code)
                } else {
                    reportPing(ping)
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
            if (!attachSession(bound, refreshBaseline = true)) {
                scheduleRebind()
            }
        }
        when (val bind = runtime.bindUserService()) {
            is BindResult.Bound -> {
                if (!attachSession(bind.session, refreshBaseline = true)) {
                    scheduleRebind()
                }
            }
            BindResult.Binding -> Unit
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
            is BindResult.Bound -> {
                if (started) {
                    attachSession(bind.session, refreshBaseline = true)
                }
                session ?: bind.session
            }
            BindResult.Binding -> {
                return ClipboardReadResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD)
            }
            is BindResult.Failed -> {
                lastErrorCode = bind.errorCode
                return ClipboardReadResult.Failure(bind.errorCode)
            }
        }
        return readFromSession(active)
    }

    /**
     * Read for the wizard's device-verified test, waiting (bounded) for a cold or freshly
     * (re)spawned UserService bind to land before giving a verdict. The bind is asynchronous —
     * the privileged host spawns the UserService child process, which then attaches its binder
     * back — so a plain [readText] on a not-yet-active route races that spawn and returns
     * PRIV_HOST_USERSERVICE_DEAD before the channel ever had a chance. That is the loop the user
     * is stuck in: the route only becomes READY (and thus started/warm) after a verified read,
     * but the verified read can never pass while it refuses to wait for the bind. Waiting also
     * makes this the app-side auto-recovery when the host is alive but the UserService died: a
     * fresh bind respawns it, and we hold on for it here instead of declaring the channel dead.
     *
     * This wait lives only on this verification path (run off the main thread from the read
     * test); the hot event/read paths stay non-blocking.
     *
     * Self-heal for a stale-but-not-yet-reaped session: we may still hold a [session] whose
     * UserService child has already died (the death callback lags a frozen/cached app, or the
     * OS reaped the child without delivering linkToDeath yet). A plain read of it returns dead,
     * which used to send the user to a PC that is not needed. Instead, when the prerequisites
     * pass (host pings and we are authorized) but the held session reads back a death code, we
     * drop the stale binder and ask the still-alive host to respawn the child on a fresh bind,
     * then fall through to the same bounded wait — no new adb shell, no weakened consent gate.
     * If the host is truly gone, the rebind below fails honestly with its own code.
     */
    override fun readTextForVerification(): ClipboardReadResult {
        val blocked = diagnoseUserActionOrMismatch()
        if (blocked != null) {
            lastErrorCode = blocked
            return ClipboardReadResult.Failure(blocked)
        }
        // A held session that reads healthy is the verdict; a null result (no session, or a
        // stale-dead one we just asked the host to respawn) falls through to the bounded wait.
        return readHeldSessionForVerification() ?: readAfterAwaitingBind()
    }

    /**
     * Reads the currently held session for the verification path, or null to fall through to a
     * fresh bind. A held session that reads back a death code means the host is alive+authorized
     * (the caller's guard passed) but the UserService child is gone: self-heal by asking the host
     * to respawn, then return null so the caller waits out that fresh bind.
     */
    private fun readHeldSessionForVerification(): ClipboardReadResult? {
        val active = session ?: return null
        val read = readFromSession(active)
        return if (read is ClipboardReadResult.Failure && isCurrentDeath(read.errorCode)) {
            requestHostRespawn(read.errorCode)
            null
        } else {
            read
        }
    }

    /**
     * App-side self-heal when the host is alive+authorized but the UserService child is dead.
     * Drops the stale session and unbinds so the next [ShizukuRuntime.bindUserService] reaches
     * the host and makes it respawn the child (the host does this itself, no new adb shell); the
     * caller then waits out that fresh bind via [readAfterAwaitingBind].
     */
    private fun requestHostRespawn(deadCode: String) {
        logger(
            "特权直读自愈：宿主在线且已授权，但持有的 UserService 读回 $deadCode；" +
                "释放陈旧绑定并请宿主重启子进程，等待新绑定落地",
        )
        session?.removeChangedListener()
        session = null
        runtime.unbindUserService()
    }

    /** The bounded bind-wait loop behind [readTextForVerification]; null outcome = keep waiting. */
    private fun readAfterAwaitingBind(): ClipboardReadResult {
        var outcome: ClipboardReadResult? = null
        var poll = 0
        while (outcome == null) {
            outcome =
                when (val bind = runtime.bindUserService()) {
                    is BindResult.Bound -> {
                        if (started && session !== bind.session) {
                            attachSession(bind.session, refreshBaseline = true)
                        }
                        readFromSession(session ?: bind.session)
                    }
                    is BindResult.Failed -> {
                        lastErrorCode = bind.errorCode
                        ClipboardReadResult.Failure(bind.errorCode)
                    }
                    BindResult.Binding ->
                        // The bind is in progress, not dead: hold for the UserService to attach.
                        if (poll >= verifyBind.polls) {
                            lastErrorCode = ShizukuErrorCodes.USERSERVICE_DEAD
                            ClipboardReadResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD)
                        } else {
                            poll += 1
                            verifyBind.sleep(verifyBind.stepMillis)
                            null
                        }
                }
        }
        return outcome
    }

    private fun readFromSession(active: ShizukuClipboardSession): ClipboardReadResult =
        when (val read = active.readText()) {
            is SessionRead.Text -> {
                lastReadSuccessAtEpochMillis = nowEpochMillis()
                lastErrorCode = null
                ClipboardReadResult.Success(read.value, read.isSensitive)
            }
            SessionRead.Empty -> ClipboardReadResult.Empty
            is SessionRead.Failed -> {
                lastErrorCode = read.errorCode
                ClipboardReadResult.Failure(read.errorCode)
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
            val code = lastErrorCode
            return if (code == null || !isCurrentDeath(code)) {
                BackendHealth(BackendHealthState.DEGRADED, checkedAt)
            } else {
                BackendHealth(degradedOrFailed(code), checkedAt, code)
            }
        }
        if (ping != null) {
            lastErrorCode = ping
            return BackendHealth(degradedOrFailed(ping), checkedAt, ping)
        }
        val listenerError = lastErrorCode
        if (listenerError == ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD) {
            return BackendHealth(degradedOrFailed(listenerError), checkedAt, listenerError)
        }
        lastErrorCode = null
        return BackendHealth(BackendHealthState.HEALTHY, checkedAt)
    }

    /**
     * Adopt [bound] as the active session and (re-)register the change listener.
     * Registration is replace-semantics on the UserService side, so calling this
     * repeatedly (start, rebind callback, scheduled rebind, periodic probe) is safe
     * and self-heals a lost registration.
     */
    private fun attachSession(bound: ShizukuClipboardSession, refreshBaseline: Boolean): Boolean {
        session = bound
        if (!bound.addChangedListener(::onChangeSignal)) {
            lastErrorCode = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
            return false
        }
        lastErrorCode = null
        rebindAttempt = 0
        runtime.cancelRebind()
        if (refreshBaseline) {
            refreshHashBaseline()
        }
        return true
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
                        isSensitive = read.isSensitive,
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
                is BindResult.Bound -> {
                    if (!attachSession(bind.session, refreshBaseline = true)) {
                        scheduleRebind()
                    }
                }
                BindResult.Binding -> Unit
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

    private fun reportPing(ping: String?): CapabilityReport {
        if (ping != null) {
            lastErrorCode = ping
            return report(ShizukuErrorCodes.probeReadState(ping), ping)
        }
        lastErrorCode = null
        return report(CapabilityState.READY, errorCode = null)
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
                    "priv_host_installed",
                    presence != ShizukuPresence.NOT_INSTALLED,
                ),
                ClipboardAuthorization(
                    "priv_host_running",
                    presence == ShizukuPresence.RUNNING,
                ),
                ClipboardAuthorization("priv_host_authorized", runtime.isAuthorized()),
            ),
            lastReadSuccessAtEpochMillis = lastReadSuccessAtEpochMillis,
            errorCode = errorCode,
        )
    }

    private fun isCurrentDeath(errorCode: String): Boolean =
        errorCode == ShizukuErrorCodes.BINDER_DEAD ||
            errorCode == ShizukuErrorCodes.USERSERVICE_DEAD ||
            errorCode == ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD

    private fun degradedOrFailed(errorCode: String): BackendHealthState =
        if (ShizukuErrorCodes.probeReadState(errorCode) == CapabilityState.DEGRADED) {
            BackendHealthState.DEGRADED
        } else {
            BackendHealthState.FAILED
        }

    companion object {
        val DEFAULT_REBIND_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private const val TAG = "ClipSyncShizuku"
        private val DEFAULT_LOGGER: (String) -> Unit = { Log.i(TAG, it) }
    }
}

/**
 * How long [ShizukuClipboardBackend.readTextForVerification] holds for an in-flight bind, and how
 * it waits between polls. [sleep] is a seam so JVM tests drive the loop without real delays.
 */
data class VerifyBindBudget(
    val polls: Int,
    val stepMillis: Long,
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        // The verification read waits up to polls x step for a cold/respawned bind to land.
        // 48 x 250ms = 12s: comfortably covers an app_process cold spawn (slowest over wireless
        // adb) while staying under the runtime's own 35s bind timeout, and well inside the
        // deliberate "测试后台读取" busy window.
        val DEFAULT = VerifyBindBudget(polls = 48, stepMillis = 250L)
    }
}
