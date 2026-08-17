package com.clipsync.android.platform.clipboard.shizuku

import android.content.ClipData
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Shizuku UserService running as shell. Binder surface is read/write/listener/health
 * only — no network, secrets, or arbitrary shell.
 */
class ClipboardUserService() : Binder(), IBinder.DeathRecipient {
    @Suppress("unused")
    constructor(context: Context) : this()

    init {
        attachInterface(null, ShizukuClipboardBinderContract.DESCRIPTOR)
        alignIdentityWithShellPackage()
    }

    private val adapterLock = Any()
    private var clipboard: Any? = null
    private var clipboardBinder: IBinder? = null
    private var adapter: IClipboardReflectionAdapter? = null
    private var systemListener: Any? = null
    private var appCallback: IBinder? = null
    private var lastHealthError: String? = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD

    private val systemListenerBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(PRIMARY_CLIP_CHANGED_DESCRIPTOR)
                    return true
                }
                TRANSACTION_DISPATCH_PRIMARY_CLIP_CHANGED -> {
                    data.enforceInterface(PRIMARY_CLIP_CHANGED_DESCRIPTOR)
                    notifyAppChanged()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(ShizukuClipboardBinderContract.DESCRIPTOR)
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_READ -> {
                data.enforceInterface(ShizukuClipboardBinderContract.DESCRIPTOR)
                writeReadReply(reply)
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_WRITE -> {
                data.enforceInterface(ShizukuClipboardBinderContract.DESCRIPTOR)
                val text = data.readString().orEmpty()
                writeWriteReply(reply, text)
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_ADD_LISTENER -> {
                data.enforceInterface(ShizukuClipboardBinderContract.DESCRIPTOR)
                appCallback = data.readStrongBinder()
                val ok = registerSystemListener()
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_REMOVE_LISTENER -> {
                data.enforceInterface(ShizukuClipboardBinderContract.DESCRIPTOR)
                unregisterSystemListener()
                appCallback = null
                reply?.writeNoException()
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_PING -> {
                data.enforceInterface(ShizukuClipboardBinderContract.DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(pingCode())
                return true
            }
            ShizukuClipboardBinderContract.TRANSACTION_DESTROY -> {
                data.enforceInterface(ShizukuClipboardBinderContract.DESCRIPTOR)
                destroy()
                reply?.writeNoException()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    @Suppress("unused")
    fun destroy() {
        unregisterSystemListener()
        appCallback = null
        synchronized(adapterLock) {
            unlinkClipboardDeath()
            clipboard = null
            clipboardBinder = null
            adapter = null
        }
    }

    override fun binderDied() {
        synchronized(adapterLock) {
            clipboard = null
            clipboardBinder = null
            adapter = null
            lastHealthError = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
        }
        notifyAppClipboardDied()
    }

    private fun writeReadReply(reply: Parcel?) {
        reply ?: return
        val current = ensureAdapter()
        if (current == null) {
            reply.writeNoException()
            reply.writeInt(ShizukuClipboardBinderContract.READ_FAILED)
            reply.writeString(lastHealthError ?: ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD)
            return
        }
        when (val result = current.getPrimaryClipText()) {
            is ClipboardAdapterResult.Text -> {
                reply.writeNoException()
                reply.writeInt(ShizukuClipboardBinderContract.READ_TEXT)
                reply.writeString(result.value)
            }
            ClipboardAdapterResult.Empty -> {
                reply.writeNoException()
                reply.writeInt(ShizukuClipboardBinderContract.READ_EMPTY)
            }
            is ClipboardAdapterResult.Failed -> {
                lastHealthError = result.errorCode
                reply.writeNoException()
                reply.writeInt(ShizukuClipboardBinderContract.READ_FAILED)
                reply.writeString(result.errorCode)
            }
        }
    }

    private fun writeWriteReply(reply: Parcel?, text: String) {
        reply ?: return
        val current = ensureAdapter()
        if (current == null) {
            reply.writeNoException()
            reply.writeInt(ShizukuClipboardBinderContract.WRITE_FAILED)
            reply.writeString(lastHealthError ?: ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD)
            return
        }
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        when (val result = current.setPrimaryClip(clip)) {
            ClipboardAdapterResult.Empty, is ClipboardAdapterResult.Text -> {
                reply.writeNoException()
                reply.writeInt(ShizukuClipboardBinderContract.WRITE_OK)
            }
            is ClipboardAdapterResult.Failed -> {
                lastHealthError = result.errorCode
                reply.writeNoException()
                reply.writeInt(ShizukuClipboardBinderContract.WRITE_FAILED)
                reply.writeString(result.errorCode)
            }
        }
    }

    private fun pingCode(): Int {
        val current = ensureAdapter() ?: return when (lastHealthError) {
            ShizukuErrorCodes.API_MISMATCH -> ShizukuClipboardBinderContract.PING_API_MISMATCH
            else -> ShizukuClipboardBinderContract.PING_CLIPBOARD_DEAD
        }
        return when (val probe = current.getPrimaryClipText()) {
            is ClipboardAdapterResult.Failed -> {
                lastHealthError = probe.errorCode
                if (probe.errorCode == ShizukuErrorCodes.API_MISMATCH) {
                    ShizukuClipboardBinderContract.PING_API_MISMATCH
                } else {
                    ShizukuClipboardBinderContract.PING_CLIPBOARD_DEAD
                }
            }
            else -> {
                lastHealthError = null
                ShizukuClipboardBinderContract.PING_OK
            }
        }
    }

    private fun ensureAdapter(): IClipboardReflectionAdapter? {
        synchronized(adapterLock) {
            val cachedBinder = clipboardBinder
            val cached = adapter
            if (cached != null && cachedBinder != null && cachedBinder.pingBinder()) {
                return cached
            }
            unlinkClipboardDeath()
            clipboard = null
            clipboardBinder = null
            adapter = null
            return try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getService = serviceManager.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "clipboard") as? IBinder
                if (binder == null || !binder.pingBinder()) {
                    lastHealthError = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
                    return null
                }
                val stub = Class.forName("android.content.IClipboard\$Stub")
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                val proxy = asInterface.invoke(null, binder) ?: run {
                    lastHealthError = ShizukuErrorCodes.API_MISMATCH
                    return null
                }
                binder.linkToDeath(this, 0)
                clipboardBinder = binder
                clipboard = proxy
                val created = IClipboardReflectionAdapter(
                    clipboard = proxy,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    callingPackage = SHELL_PACKAGE,
                )
                adapter = created
                lastHealthError = null
                created
            } catch (error: Throwable) {
                lastHealthError = IClipboardReflectionAdapter.mapInvokeError(error)
                null
            }
        }
    }

    private fun registerSystemListener(): Boolean {
        val current = ensureAdapter() ?: return false
        val listener = systemListener() ?: return false
        return when (val result = current.addPrimaryClipChangedListener(listener)) {
            is ClipboardAdapterResult.Failed -> {
                lastHealthError = result.errorCode
                false
            }
            else -> true
        }
    }

    private fun unregisterSystemListener() {
        val current = adapter
        val listener = systemListener
        if (current != null && listener != null) {
            current.removePrimaryClipChangedListener(listener)
        }
    }

    private fun systemListener(): Any? {
        systemListener?.let { return it }
        return try {
            val stubClass = Class.forName("android.content.IOnPrimaryClipChangedListener\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, systemListenerBinder).also { systemListener = it }
        } catch (_: Exception) {
            lastHealthError = ShizukuErrorCodes.API_MISMATCH
            null
        }
    }

    private fun notifyAppChanged() {
        transactToApp(ShizukuClipboardBinderContract.TRANSACTION_ON_CHANGED)
    }

    private fun notifyAppClipboardDied() {
        transactToApp(ShizukuClipboardBinderContract.TRANSACTION_ON_CLIPBOARD_DIED)
    }

    private fun transactToApp(code: Int) {
        val callback = appCallback ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuClipboardBinderContract.CALLBACK_DESCRIPTOR)
            callback.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            // App process gone; Shizuku will tear this service down.
        } finally {
            data.recycle()
        }
    }

    private fun unlinkClipboardDeath() {
        try {
            clipboardBinder?.unlinkToDeath(this, 0)
        } catch (_: Exception) {
            // Already unlinked.
        }
    }

    private fun alignIdentityWithShellPackage() {
        if (android.os.Process.myUid() != 0) {
            return
        }
        try {
            android.system.Os.setgid(SHELL_UID)
            android.system.Os.setuid(SHELL_UID)
        } catch (error: Exception) {
            Log.w(TAG, "shell identity align failed: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ClipSyncShizuku"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_UID = 2000
        private const val CLIP_LABEL = "ClipSync"
        private const val PRIMARY_CLIP_CHANGED_DESCRIPTOR =
            "android.content.IOnPrimaryClipChangedListener"
        private const val TRANSACTION_DISPATCH_PRIMARY_CLIP_CHANGED =
            IBinder.FIRST_CALL_TRANSACTION
    }
}
