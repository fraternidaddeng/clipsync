package com.clipsync.android.platform.clipboard.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Device-only privileged-host client facade. JVM unit tests must inject
 * [ShizukuRuntime] fakes and never construct this class.
 */
class AndroidShizukuRuntime(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : ShizukuRuntime {
    override val systemVersion: String = android.os.Build.VERSION.SDK_INT.toString()
    override val sdkInt: Int = android.os.Build.VERSION.SDK_INT

    private val lock = Any()
    private var session: ShizukuClipboardSessionProxy? = null
    private var deathListener: ((BinderDeathKind) -> Unit)? = null
    private var onBound: ((ShizukuClipboardSession) -> Unit)? = null
    private var rebindRunnable: Runnable? = null
    private var bindTimeoutRunnable: Runnable? = null
    private var binding: Boolean = false
    private var shizukuDeathLinked: Boolean = false
    private var shizukuDeathRecipientLinked: Boolean = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ClipboardUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("clipsync-clipboard")
        .debuggable(false)
        .version(USER_SERVICE_VERSION)

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE) {
                return@OnRequestPermissionResultListener
            }
            finishAuth(grantResult == PackageManager.PERMISSION_GRANTED)
        }

    private var pendingAuthResult: ((Boolean) -> Unit)? = null
    private var authSettleRunnable: Runnable? = null
    private var authBinderListener: Shizuku.OnBinderReceivedListener? = null

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(lock) {
            session = null
            binding = false
            shizukuDeathRecipientLinked = false
        }
        cancelBindTimeout()
        deathListener?.invoke(BinderDeathKind.SHIZUKU)
    }

    private val shizukuDeathRecipient = IBinder.DeathRecipient {
        synchronized(lock) {
            session = null
            binding = false
            shizukuDeathRecipientLinked = false
        }
        cancelBindTimeout()
        deathListener?.invoke(BinderDeathKind.SHIZUKU)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            cancelBindTimeout()
            if (service == null || !service.pingBinder()) {
                synchronized(lock) { binding = false }
                deathListener?.invoke(BinderDeathKind.USER_SERVICE)
                return
            }
            val proxy = ShizukuClipboardSessionProxy(
                remote = service,
                onClipboardDied = { deathListener?.invoke(BinderDeathKind.CLIPBOARD) },
            )
            try {
                service.linkToDeath(
                    {
                        synchronized(lock) {
                            if (session === proxy) {
                                session = null
                            }
                            binding = false
                        }
                        cancelBindTimeout()
                        deathListener?.invoke(BinderDeathKind.USER_SERVICE)
                    },
                    0,
                )
            } catch (_: Exception) {
                synchronized(lock) { binding = false }
                deathListener?.invoke(BinderDeathKind.USER_SERVICE)
                return
            }
            synchronized(lock) {
                session = proxy
                binding = false
            }
            onBound?.invoke(proxy)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                session = null
                binding = false
            }
            cancelBindTimeout()
            deathListener?.invoke(BinderDeathKind.USER_SERVICE)
        }
    }

    override fun presence(): ShizukuPresence {
        return try {
            if (Shizuku.pingBinder()) {
                ShizukuPresence.RUNNING
            } else {
                // The privileged host is bundled in this APK.
                ShizukuPresence.NOT_RUNNING
            }
        } catch (_: Exception) {
            ShizukuPresence.NOT_RUNNING
        }
    }

    override fun isAuthorized(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun isPreV11(): Boolean {
        return try {
            Shizuku.isPreV11()
        } catch (_: Exception) {
            false
        }
    }

    override fun requestAuthorization(onResult: (granted: Boolean) -> Unit) {
        cancelAuthSettle()
        try {
            if (!Shizuku.pingBinder()) {
                onResult(false)
                return
            }
            if (permissionGranted()) {
                onResult(true)
                return
            }
            // Do not call onBinderReceived(null) here. That tears down the
            // living host binder, fires dead listeners, and makes
            // requestPermission throw before the host can reply.
            pendingAuthResult = onResult
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.requestPermission(REQUEST_CODE)
            Log.i(TAG, "requestPermission sent")
        } catch (error: Exception) {
            Log.w(TAG, "requestPermission: ${error.javaClass.simpleName}")
            pendingAuthResult = onResult
        }
        if (completeAuthIfGranted()) {
            return
        }
        if (pendingAuthResult !== onResult) {
            return
        }
        watchBinderForAuth()
        scheduleAuthPoll(onResult)
    }

    override fun bindUserService(): BindResult {
        if (isPreV11()) {
            return BindResult.Failed(ShizukuErrorCodes.API_MISMATCH)
        }
        when (presence()) {
            ShizukuPresence.NOT_INSTALLED ->
                return BindResult.Failed(ShizukuErrorCodes.NOT_INSTALLED)
            ShizukuPresence.NOT_RUNNING ->
                return BindResult.Failed(ShizukuErrorCodes.NOT_RUNNING)
            ShizukuPresence.RUNNING -> Unit
        }
        if (!isAuthorized()) {
            return BindResult.Failed(ShizukuErrorCodes.NOT_AUTHORIZED)
        }
        linkShizukuDeath()
        synchronized(lock) {
            session?.let { return BindResult.Bound(it) }
            if (binding) {
                return BindResult.Binding
            }
            binding = true
        }
        scheduleBindTimeout()
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (_: Exception) {
            synchronized(lock) { binding = false }
            cancelBindTimeout()
            return BindResult.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
        }
        synchronized(lock) {
            session?.let { return BindResult.Bound(it) }
            return BindResult.Binding
        }
    }

    override fun unbindUserService() {
        synchronized(lock) {
            session = null
            binding = false
        }
        cancelBindTimeout()
        unlinkShizukuDeath()
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    override fun currentSession(): ShizukuClipboardSession? = synchronized(lock) { session }

    override fun setDeathListener(listener: ((BinderDeathKind) -> Unit)?) {
        deathListener = listener
    }

    override fun setOnBound(listener: ((ShizukuClipboardSession) -> Unit)?) {
        onBound = listener
    }

    override fun scheduleRebind(delayMillis: Long, action: () -> Unit) {
        cancelRebind()
        val runnable = Runnable { action() }
        rebindRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    override fun cancelRebind() {
        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindRunnable = null
    }

    private fun permissionGranted(): Boolean =
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun permissionGrantedOrFalse(): Boolean =
        try {
            permissionGranted()
        } catch (_: Exception) {
            false
        }

    private fun completeAuthIfGranted(): Boolean {
        if (pendingAuthResult == null || !permissionGrantedOrFalse()) {
            return false
        }
        finishAuth(true)
        return true
    }

    private fun finishAuth(granted: Boolean) {
        cancelAuthSettle()
        val pending = pendingAuthResult
        pendingAuthResult = null
        pending?.invoke(granted)
    }

    private fun watchBinderForAuth() {
        if (authBinderListener != null) {
            return
        }
        val listener = Shizuku.OnBinderReceivedListener {
            completeAuthIfGranted()
        }
        authBinderListener = listener
        Shizuku.addBinderReceivedListenerSticky(listener)
    }

    private fun scheduleAuthPoll(onResult: (granted: Boolean) -> Unit) {
        val deadline = System.currentTimeMillis() + AUTH_SETTLE_MS
        val poll = object : Runnable {
            override fun run() {
                if (pendingAuthResult !== onResult) {
                    return
                }
                if (permissionGrantedOrFalse()) {
                    finishAuth(true)
                    return
                }
                if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    finishAuth(false)
                    return
                }
                if (System.currentTimeMillis() >= deadline) {
                    finishAuth(false)
                    return
                }
                mainHandler.postDelayed(this, AUTH_POLL_MS)
            }
        }
        authSettleRunnable = poll
        mainHandler.postDelayed(poll, AUTH_POLL_MS)
    }

    private fun cancelAuthSettle() {
        authSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        authSettleRunnable = null
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        authBinderListener?.let { listener ->
            runCatching { Shizuku.removeBinderReceivedListener(listener) }
        }
        authBinderListener = null
    }

    private fun scheduleBindTimeout() {
        cancelBindTimeout()
        val timeout = Runnable {
            val timedOut = synchronized(lock) {
                if (session == null && binding) {
                    binding = false
                    true
                } else {
                    false
                }
            }
            if (timedOut) {
                runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                deathListener?.invoke(BinderDeathKind.USER_SERVICE)
            }
        }
        bindTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, BIND_TIMEOUT_MS)
    }

    private fun cancelBindTimeout() {
        bindTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bindTimeoutRunnable = null
    }

    private fun linkShizukuDeath() {
        if (!shizukuDeathLinked) {
            try {
                Shizuku.addBinderDeadListener(binderDeadListener)
                shizukuDeathLinked = true
            } catch (_: Exception) {
                shizukuDeathLinked = false
                return
            }
        }
        if (shizukuDeathRecipientLinked) {
            return
        }
        try {
            val binder = Shizuku.getBinder() ?: return
            binder.linkToDeath(shizukuDeathRecipient, 0)
            shizukuDeathRecipientLinked = true
        } catch (_: Exception) {
            // Binder already died or was replaced; bindUserService will retry.
        }
    }

    private fun unlinkShizukuDeath() {
        if (shizukuDeathLinked) {
            try {
                Shizuku.removeBinderDeadListener(binderDeadListener)
            } catch (_: Exception) {
            }
            shizukuDeathLinked = false
        }
        if (shizukuDeathRecipientLinked) {
            try {
                Shizuku.getBinder()?.unlinkToDeath(shizukuDeathRecipient, 0)
            } catch (_: Exception) {
            }
            shizukuDeathRecipientLinked = false
        }
    }

    companion object {
        const val REQUEST_CODE = 0xC11
        const val USER_SERVICE_VERSION = 2
        const val AUTH_SETTLE_MS = 12_000L
        const val AUTH_POLL_MS = 100L
        const val BIND_TIMEOUT_MS = 35_000L
        private const val TAG = "ClipSyncShizuku"
    }
}
