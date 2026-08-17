package com.clipsync.android.platform.clipboard.shizuku

/**
 * Facade over Shizuku / Binder so JVM tests never construct Shizuku classes.
 * The real implementation lives in [AndroidShizukuRuntime].
 */
interface ShizukuRuntime {
    val systemVersion: String
    val sdkInt: Int

    fun presence(): ShizukuPresence

    fun isAuthorized(): Boolean

    fun isPreV11(): Boolean

    fun requestAuthorization(onResult: (granted: Boolean) -> Unit)

    fun bindUserService(): BindResult

    fun unbindUserService()

    fun currentSession(): ShizukuClipboardSession?

    fun setDeathListener(listener: ((BinderDeathKind) -> Unit)?)

    fun setOnBound(listener: ((ShizukuClipboardSession) -> Unit)?)

    fun scheduleRebind(delayMillis: Long, action: () -> Unit)

    fun cancelRebind()
}

enum class ShizukuPresence {
    NOT_INSTALLED,
    NOT_RUNNING,
    RUNNING,
}

enum class BinderDeathKind {
    SHIZUKU,
    USER_SERVICE,
    CLIPBOARD,
}

sealed interface BindResult {
    data class Bound(val session: ShizukuClipboardSession) : BindResult

    data class Failed(val errorCode: String) : BindResult
}

interface ShizukuClipboardSession {
    fun readText(): SessionRead

    fun writeText(text: String): SessionWrite

    fun addChangedListener(onChanged: () -> Unit)

    fun removeChangedListener()

    fun pingHealth(): String?
}

sealed interface SessionRead {
    data class Text(val value: String) : SessionRead

    data object Empty : SessionRead

    data class Failed(val errorCode: String) : SessionRead
}

sealed interface SessionWrite {
    data object Success : SessionWrite

    data class Failed(val errorCode: String) : SessionWrite
}
