package com.clipsync.android.platform.clipboard.shizuku

internal class FakeShizukuRuntime(
    override var systemVersion: String = "35",
    override var sdkInt: Int = 35,
) : ShizukuRuntime {
    var presenceState: ShizukuPresence = ShizukuPresence.RUNNING
    var authorized: Boolean = true
    var preV11: Boolean = false
    var session: FakeShizukuClipboardSession? = FakeShizukuClipboardSession()
    var bindError: String? = null
    var binding: Boolean = false
    var bindCount: Int = 0
    var unbindCount: Int = 0

    /**
     * Models the privileged host respawning the UserService child when the app drops a
     * stale binder and rebinds: [unbindUserService] clears the current session and enters
     * an in-flight ("binding") state so the next [bindUserService] answers [BindResult.Binding],
     * exactly as a real cold/respawned bind does before the child attaches back.
     */
    var respawnAfterUnbind: Boolean = false
    var authRequests: Int = 0
    var pendingAuth: ((Boolean) -> Unit)? = null
    var pendingRebind: (() -> Unit)? = null
    var lastRebindDelayMillis: Long? = null
    var rebindScheduleCount: Int = 0
    var capturedDeathListener: ((BinderDeathKind) -> Unit)? = null
    var capturedOnBound: ((ShizukuClipboardSession) -> Unit)? = null

    override fun presence(): ShizukuPresence = presenceState

    override fun isAuthorized(): Boolean = authorized

    override fun isPreV11(): Boolean = preV11

    override fun requestAuthorization(onResult: (granted: Boolean) -> Unit) {
        authRequests += 1
        pendingAuth = onResult
    }

    fun grantAuthorization() {
        authorized = true
        pendingAuth?.invoke(true)
        pendingAuth = null
    }

    fun denyAuthorization() {
        authorized = false
        pendingAuth?.invoke(false)
        pendingAuth = null
    }

    override fun bindUserService(): BindResult {
        bindCount += 1
        if (preV11) {
            return BindResult.Failed(ShizukuErrorCodes.API_MISMATCH)
        }
        when (presenceState) {
            ShizukuPresence.NOT_INSTALLED ->
                return BindResult.Failed(ShizukuErrorCodes.NOT_INSTALLED)
            ShizukuPresence.NOT_RUNNING ->
                return BindResult.Failed(ShizukuErrorCodes.NOT_RUNNING)
            ShizukuPresence.RUNNING -> Unit
        }
        if (!authorized) {
            return BindResult.Failed(ShizukuErrorCodes.NOT_AUTHORIZED)
        }
        bindError?.let { return BindResult.Failed(it) }
        if (binding) {
            return BindResult.Binding
        }
        val bound = session ?: return BindResult.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
        return BindResult.Bound(bound)
    }

    override fun unbindUserService() {
        unbindCount += 1
        if (respawnAfterUnbind) {
            session = null
            binding = true
        }
    }

    override fun currentSession(): ShizukuClipboardSession? = session

    override fun setDeathListener(listener: ((BinderDeathKind) -> Unit)?) {
        capturedDeathListener = listener
    }

    override fun setOnBound(listener: ((ShizukuClipboardSession) -> Unit)?) {
        capturedOnBound = listener
    }

    override fun scheduleRebind(delayMillis: Long, action: () -> Unit) {
        rebindScheduleCount += 1
        lastRebindDelayMillis = delayMillis
        pendingRebind = action
    }

    override fun cancelRebind() {
        pendingRebind = null
    }

    fun fireRebind() {
        val action = pendingRebind
        pendingRebind = null
        action?.invoke()
    }

    fun fireDeath(kind: BinderDeathKind) {
        capturedDeathListener?.invoke(kind)
    }
}

internal class FakeShizukuClipboardSession : ShizukuClipboardSession {
    var clip: SessionRead = SessionRead.Empty
    var writeResult: SessionWrite = SessionWrite.Success
    var healthError: String? = null
    var listener: (() -> Unit)? = null
    var addListenerCount: Int = 0
    var removeListenerCount: Int = 0
    var addListenerOk: Boolean = true
    val writes = mutableListOf<String>()

    override fun readText(): SessionRead = clip

    override fun writeText(text: String): SessionWrite {
        writes += text
        return writeResult
    }

    override fun addChangedListener(onChanged: () -> Unit): Boolean {
        addListenerCount += 1
        if (!addListenerOk) {
            return false
        }
        listener = onChanged
        return true
    }

    override fun removeChangedListener() {
        removeListenerCount += 1
        listener = null
    }

    override fun pingHealth(): String? = healthError

    fun emitChanged() {
        listener?.invoke()
    }
}
