package com.clipsync.android.platform.clipboard.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Device-only Shizuku facade. JVM unit tests must inject [ShizukuRuntime]
 * fakes and never construct this class.
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
    private var binding: Boolean = false
    private var shizukuDeathLinked: Boolean = false

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
            pendingAuthResult?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
            pendingAuthResult = null
        }

    private var pendingAuthResult: ((Boolean) -> Unit)? = null

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(lock) {
            session = null
            binding = false
        }
        deathListener?.invoke(BinderDeathKind.SHIZUKU)
    }

    private val shizukuDeathRecipient = IBinder.DeathRecipient {
        synchronized(lock) {
            session = null
            binding = false
        }
        deathListener?.invoke(BinderDeathKind.SHIZUKU)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
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
            deathListener?.invoke(BinderDeathKind.USER_SERVICE)
        }
    }

    override fun presence(): ShizukuPresence {
        return try {
            if (Shizuku.pingBinder()) {
                ShizukuPresence.RUNNING
            } else if (isShizukuPackageInstalled()) {
                ShizukuPresence.NOT_RUNNING
            } else {
                ShizukuPresence.NOT_INSTALLED
            }
        } catch (_: Exception) {
            if (isShizukuPackageInstalled()) {
                ShizukuPresence.NOT_RUNNING
            } else {
                ShizukuPresence.NOT_INSTALLED
            }
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
        try {
            if (!Shizuku.pingBinder()) {
                onResult(false)
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                onResult(true)
                return
            }
            pendingAuthResult = onResult
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Exception) {
            onResult(false)
        }
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
            if (!binding) {
                binding = true
                try {
                    Shizuku.bindUserService(userServiceArgs, connection)
                } catch (_: Exception) {
                    binding = false
                    return BindResult.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
                }
            }
        }
        synchronized(lock) {
            session?.let { return BindResult.Bound(it) }
            return BindResult.Failed(ShizukuErrorCodes.USERSERVICE_DEAD)
        }
    }

    override fun unbindUserService() {
        synchronized(lock) {
            session = null
            binding = false
        }
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

    private fun linkShizukuDeath() {
        if (shizukuDeathLinked) {
            return
        }
        try {
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.getBinder()?.linkToDeath(shizukuDeathRecipient, 0)
            shizukuDeathLinked = true
        } catch (_: Exception) {
            shizukuDeathLinked = false
        }
    }

    private fun isShizukuPackageInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 0xC11
        const val USER_SERVICE_VERSION = 1
    }
}
