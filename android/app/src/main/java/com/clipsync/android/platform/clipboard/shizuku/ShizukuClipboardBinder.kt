package com.clipsync.android.platform.clipboard.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Minimal UserService binder surface: read text, write text, add/remove
 * listener, health ping. No network, secrets, or arbitrary shell.
 */
internal object ShizukuClipboardBinderContract {
    const val DESCRIPTOR = "com.clipsync.android.platform.clipboard.shizuku.UserService"
    const val CALLBACK_DESCRIPTOR = "com.clipsync.android.platform.clipboard.shizuku.Callback"

    const val TRANSACTION_READ = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_WRITE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_ADD_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_REMOVE_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_DESTROY = 16777115

    const val TRANSACTION_ON_CHANGED = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_ON_CLIPBOARD_DIED = IBinder.FIRST_CALL_TRANSACTION + 1

    const val PING_OK = 0
    const val PING_CLIPBOARD_DEAD = 1
    const val PING_API_MISMATCH = 2

    const val READ_EMPTY = 0
    const val READ_TEXT = 1
    const val READ_FAILED = 2

    const val WRITE_OK = 0
    const val WRITE_FAILED = 1
}

internal class ShizukuClipboardSessionProxy(
    private val remote: IBinder,
    private val onClipboardDied: () -> Unit = {},
) : ShizukuClipboardSession {
    private var callbackBinder: ChangeCallbackBinder? = null

    override fun readText(): SessionRead {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuClipboardBinderContract.DESCRIPTOR)
            if (!transactTimed(ShizukuClipboardBinderContract.TRANSACTION_READ, data, reply)) {
                return SessionRead.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
            }
            reply.readException()
            when (reply.readInt()) {
                ShizukuClipboardBinderContract.READ_TEXT -> SessionRead.Text(reply.readString().orEmpty())
                ShizukuClipboardBinderContract.READ_EMPTY -> SessionRead.Empty
                else -> SessionRead.Failed(
                    reply.readString() ?: ShizukuErrorCodes.USERSERVICE_DEAD,
                )
            }
        } catch (_: Exception) {
            SessionRead.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun writeText(text: String): SessionWrite {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuClipboardBinderContract.DESCRIPTOR)
            data.writeString(text)
            if (!transactTimed(ShizukuClipboardBinderContract.TRANSACTION_WRITE, data, reply)) {
                return SessionWrite.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
            }
            reply.readException()
            if (reply.readInt() == ShizukuClipboardBinderContract.WRITE_OK) {
                SessionWrite.Success
            } else {
                SessionWrite.Failed(
                    reply.readString() ?: ShizukuErrorCodes.USERSERVICE_DEAD,
                )
            }
        } catch (_: Exception) {
            SessionWrite.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun addChangedListener(onChanged: () -> Unit): Boolean {
        val callback = ChangeCallbackBinder(onChanged, onClipboardDied)
        // Keep [previous] reachable until this transact returns.
        // ClipboardUserService.linkToDeath on the old binder and treats
        // binderDied as "app process gone" (exitProcess). Dropping the
        // Java object before the server unlinks it lets GC fire death
        // and kill :clipsync-clipboard during probe/refresh re-register.
        val previous = callbackBinder
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuClipboardBinderContract.DESCRIPTOR)
            data.writeStrongBinder(callback)
            if (!transactTimed(ShizukuClipboardBinderContract.TRANSACTION_ADD_LISTENER, data, reply)) {
                callbackBinder = previous
                return false
            }
            reply.readException()
            val ok = reply.readInt() == 1
            callbackBinder = if (ok) callback else previous
            ok
        } catch (_: Exception) {
            callbackBinder = previous
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun removeChangedListener() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuClipboardBinderContract.DESCRIPTOR)
            transactTimed(ShizukuClipboardBinderContract.TRANSACTION_REMOVE_LISTENER, data, reply)
            reply.readException()
        } catch (_: Exception) {
            // Best-effort unregister.
        } finally {
            data.recycle()
            reply.recycle()
            callbackBinder = null
        }
    }

    override fun pingHealth(): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuClipboardBinderContract.DESCRIPTOR)
            if (!remote.pingBinder()) {
                return ShizukuErrorCodes.USERSERVICE_DEAD
            }
            if (!transactTimed(ShizukuClipboardBinderContract.TRANSACTION_PING, data, reply)) {
                return ShizukuErrorCodes.USERSERVICE_DEAD
            }
            reply.readException()
            when (reply.readInt()) {
                ShizukuClipboardBinderContract.PING_OK -> null
                ShizukuClipboardBinderContract.PING_CLIPBOARD_DEAD ->
                    ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
                ShizukuClipboardBinderContract.PING_API_MISMATCH ->
                    ShizukuErrorCodes.API_MISMATCH
                else -> ShizukuErrorCodes.USERSERVICE_DEAD
            }
        } catch (_: Exception) {
            ShizukuErrorCodes.USERSERVICE_DEAD
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun asBinder(): IBinder = remote

    private fun transactTimed(code: Int, data: Parcel, reply: Parcel): Boolean {
        val task = transactExecutor.submit<Boolean> {
            remote.transact(code, data, reply, 0)
        }
        return try {
            task.get(TRANSACT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(true)
            false
        }
    }

    private companion object {
        const val TRANSACT_TIMEOUT_MS = 3_000L
        val transactExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "clipsync-shizuku-transact").apply { isDaemon = true }
        }
    }
}

internal class ChangeCallbackBinder(
    private val onChanged: () -> Unit,
    private val onClipboardDied: () -> Unit = {},
) : Binder() {
    init {
        attachInterface(null, ShizukuClipboardBinderContract.CALLBACK_DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(ShizukuClipboardBinderContract.CALLBACK_DESCRIPTOR)
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_ON_CHANGED -> {
                data.enforceInterface(ShizukuClipboardBinderContract.CALLBACK_DESCRIPTOR)
                onChanged()
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_ON_CLIPBOARD_DIED -> {
                data.enforceInterface(ShizukuClipboardBinderContract.CALLBACK_DESCRIPTOR)
                onClipboardDied()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}
