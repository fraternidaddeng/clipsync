package com.clipsync.android.platform.clipboard.shizuku.host

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteCallbackList
import android.system.Os
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import moe.shizuku.api.BinderContainer
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.ShizukuApiConstants

/**
 * Privileged Java daemon started as uid 0/2000 via `app_process`. Speaks the
 * Shizuku-API v13 binder used by `dev.rikka.shizuku:api:13.1.5` so ClipSync
 * can bind [ClipboardUserService] without the official manager APK.
 *
 * `newProcess` / `transactRemote` / rish are not implemented.
 */
class PrivilegedHostService : IShizukuService.Stub() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val clients = mutableListOf<Client>()
    private var userService: UserServiceSlot? = null
    private var resendTicks = 0
    private val resend = object : Runnable {
        override fun run() {
            sendBinderToApp()
            resendTicks += 1
            val delay = if (resendTicks < PrivilegedHostConstants.BINDER_RESEND_FAST_TICKS) {
                PrivilegedHostConstants.BINDER_RESEND_FAST_MS
            } else {
                PrivilegedHostConstants.BINDER_RESEND_SLOW_MS
            }
            handler.postDelayed(this, delay)
        }
    }

    init {
        handler.post(resend)
    }

    override fun getVersion(): Int = PrivilegedHostConstants.SERVER_VERSION

    override fun getUid(): Int = Os.getuid()

    override fun checkPermission(permission: String?): Int {
        // Host identity is already shell/root. ClipSync does not query this.
        return 0
    }

    override fun newProcess(
        cmd: Array<out String>?,
        env: Array<out String>?,
        dir: String?,
    ): moe.shizuku.server.IRemoteProcess {
        throw SecurityException("newProcess is not implemented")
    }

    override fun getSELinuxContext(): String? {
        return runCatching {
            Class.forName("android.os.SELinux")
                .getMethod("getContext")
                .invoke(null) as? String
        }.getOrNull()
    }

    override fun getSystemProperty(name: String?, defaultValue: String?): String {
        throw SecurityException("getSystemProperty is not implemented")
    }

    override fun setSystemProperty(name: String?, value: String?) {
        throw SecurityException("setSystemProperty is not implemented")
    }

    override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int {
        requireCallerIsClient("addUserService")
        conn ?: throw IllegalArgumentException("connection is null")
        args ?: throw IllegalArgumentException("options is null")
        @Suppress("DEPRECATION")
        val component = args.getParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT)
            as? ComponentName ?: throw IllegalArgumentException("component is null")
        if (component.packageName != PrivilegedHostConstants.PACKAGE_NAME) {
            throw SecurityException("package not owned")
        }
        val version = args.getInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 1)
        val daemon = args.getBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, true)
        val suffix = args.getString(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME)
            ?: throw IllegalArgumentException("process name suffix must not be null")
        val noCreate = args.getBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, false)
        val className = component.className
        synchronized(this) {
            val existing = userService
            if (noCreate) {
                if (existing?.binder?.pingBinder() == true) {
                    existing.callbacks.register(conn)
                    existing.broadcastConnected()
                    return existing.version
                }
                return -1
            }
            if (existing != null && existing.version == version && existing.binder?.pingBinder() == true) {
                existing.daemon = daemon
                existing.callbacks.register(conn)
                existing.broadcastConnected()
                return 0
            }
            existing?.destroy()
            val slot = UserServiceSlot(version, daemon, className, suffix)
            slot.callbacks.register(conn)
            userService = slot
            slot.scheduleTimeout()
            executor.execute { startUserServiceProcess(slot) }
            return 0
        }
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int {
        requireCallerIsClient("removeUserService")
        args ?: return 1
        val remove = if (args.containsKey(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE)) {
            args.getBoolean(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE)
        } else {
            true
        }
        synchronized(this) {
            val slot = userService ?: return 1
            if (remove) {
                slot.destroy()
                userService = null
            } else if (conn != null) {
                slot.callbacks.unregister(conn)
            }
        }
        return 0
    }

    override fun requestPermission(requestCode: Int) {
        val client = requireClient()
        client.application.dispatchRequestPermissionResult(
            requestCode,
            allowedBundle(true),
        )
    }

    override fun checkSelfPermission(): Boolean {
        if (Binder.getCallingUid() == Os.getuid() || Binder.getCallingPid() == Os.getpid()) {
            return true
        }
        requireClient()
        return true
    }

    override fun shouldShowRequestPermissionRationale(): Boolean = false

    override fun attachApplication(application: IShizukuApplication?, args: Bundle?) {
        if (application == null || args == null) {
            return
        }
        val packageName = args.getString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME)
        if (packageName != PrivilegedHostConstants.PACKAGE_NAME) {
            throw SecurityException("package not owned")
        }
        val uid = Binder.getCallingUid()
        if (uid != Os.getuid() && packageName !in packagesForUid(uid)) {
            throw SecurityException("package not owned by caller")
        }
        val apiVersion = args.getInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1)
        val pid = Binder.getCallingPid()
        val client = Client(uid, pid, application, apiVersion)
        synchronized(this) {
            clients.removeAll { it.uid == uid && it.pid == pid }
            clients.add(client)
        }
        runCatching {
            application.asBinder().linkToDeath(
                {
                    synchronized(this) { clients.remove(client) }
                },
                0,
            )
        }
        val reply = Bundle()
        reply.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, Os.getuid())
        reply.putInt(
            ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION,
            if (apiVersion == -1) 12 else PrivilegedHostConstants.SERVER_VERSION,
        )
        reply.putInt(
            ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION,
            PrivilegedHostConstants.SERVER_PATCH_VERSION,
        )
        reply.putString(
            ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT,
            getSELinuxContext(),
        )
        reply.putBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, true)
        reply.putBoolean(
            ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE,
            false,
        )
        application.bindApplication(reply)
    }

    override fun exit() {
        if (Binder.getCallingUid() != Os.getuid() && Binder.getCallingPid() != Os.getpid()) {
            throw SecurityException("exit")
        }
        System.exit(0)
    }

    override fun attachUserService(binder: IBinder?, options: Bundle?) {
        binder ?: throw IllegalArgumentException("binder is null")
        val token = options?.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN)
            ?: throw IllegalArgumentException("token is null")
        synchronized(this) {
            val slot = userService
            if (slot == null || slot.token != token) {
                throw IllegalArgumentException("unable to find token")
            }
            slot.attachBinder(binder)
        }
    }

    override fun dispatchPackageChanged(intent: android.content.Intent?) = Unit

    override fun isHidden(uid: Int): Boolean = false

    override fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle?,
    ) = Unit

    override fun getFlagsForUid(uid: Int, mask: Int): Int = 0

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) = Unit

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            throw SecurityException("transactRemote is not implemented")
        }
        if (code == 14) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val binder = data.readStrongBinder()
            val packageName = data.readString()
            val args = Bundle()
            args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName)
            args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1)
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args)
            reply?.writeNoException()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun startUserServiceProcess(slot: UserServiceSlot) {
        val apk = hostApkPath() ?: run {
            Log.e(TAG, "apk path missing")
            return
        }
        val cmd = PrivilegedHostScript.userServiceCommand(
            apkPath = apk,
            token = slot.token,
            packageName = PrivilegedHostConstants.PACKAGE_NAME,
            className = slot.className,
            processNameSuffix = slot.processNameSuffix,
            callingUid = slot.callingUid,
        )
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val code = process.waitFor()
            if (code != 0) {
                Log.e(TAG, "user service sh exit $code")
            }
        } catch (error: Exception) {
            Log.e(TAG, "user service start failed: ${error.javaClass.simpleName}")
        }
    }

    private fun sendBinderToApp() {
        HiddenApis.addPowerSaveTempWhitelist(
            PrivilegedHostConstants.PACKAGE_NAME,
            30_000L,
            0,
        )
        val name = PrivilegedHostConstants.PROVIDER_AUTHORITY
        val token = Binder()
        val provider = HiddenApis.getContentProviderExternal(name, 0, token, name) ?: return
        try {
            val remote = HiddenApis.providerBinder(provider)
            if (remote == null || !remote.pingBinder()) {
                return
            }
            val extra = Bundle()
            extra.putParcelable(PrivilegedHostConstants.EXTRA_BINDER, BinderContainer(this))
            HiddenApis.callProvider(provider, name, PrivilegedHostConstants.METHOD_SEND_BINDER, extra)
        } catch (error: Exception) {
            Log.w(TAG, "sendBinder: ${error.javaClass.simpleName}")
        } finally {
            HiddenApis.removeContentProviderExternal(name, token)
        }
    }

    private fun requireCallerIsClient(func: String) {
        if (Binder.getCallingUid() == Os.getuid()) {
            return
        }
        requireClient(func)
    }

    private fun requireClient(func: String = "call"): Client {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        synchronized(this) {
            return clients.firstOrNull { it.uid == uid && it.pid == pid }
                ?: throw SecurityException("Permission Denial: $func is not an attached client")
        }
    }

    private fun packagesForUid(uid: Int): Set<String> {
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("getService", String::class.java)
                .invoke(null, "package") as? IBinder ?: return emptySet()
            val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val pm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, raw)
                ?: return emptySet()
            val method = pm.javaClass.methods.first {
                it.name == "getPackagesForUid" && it.parameterTypes.size == 1
            }
            (method.invoke(pm, uid) as? Array<*>)
                ?.filterIsInstance<String>()
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private fun hostApkPath(): String? {
        val fromClasspath = System.getProperty("java.class.path")
            ?.split(':', ';')
            ?.firstOrNull { it.endsWith(".apk") && File(it).isFile }
        if (fromClasspath != null) {
            return fromClasspath
        }
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("getService", String::class.java)
                .invoke(null, "package") as? IBinder ?: return null
            val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val pm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, raw)
                ?: return null
            val methods = pm.javaClass.methods.filter { it.name == "getApplicationInfo" }
            val info = when {
                methods.any {
                    it.parameterTypes.size == 3 &&
                        it.parameterTypes[1] == Long::class.javaPrimitiveType
                } ->
                    methods.first {
                        it.parameterTypes.size == 3 &&
                            it.parameterTypes[1] == Long::class.javaPrimitiveType
                    }.invoke(pm, PrivilegedHostConstants.PACKAGE_NAME, 0L, 0)
                methods.any { it.parameterTypes.size == 3 } ->
                    methods.first { it.parameterTypes.size == 3 }
                        .invoke(pm, PrivilegedHostConstants.PACKAGE_NAME, 0, 0)
                methods.any { it.parameterTypes.size == 2 } ->
                    methods.first { it.parameterTypes.size == 2 }
                        .invoke(pm, PrivilegedHostConstants.PACKAGE_NAME, 0)
                else -> null
            } ?: return null
            info.javaClass.getField("sourceDir").get(info) as? String
        }.getOrNull()
    }

    private inner class UserServiceSlot(
        val version: Int,
        var daemon: Boolean,
        val className: String,
        val processNameSuffix: String,
    ) {
        val token: String = UUID.randomUUID().toString() + "-" + System.currentTimeMillis()
        val callingUid: Int = Binder.getCallingUid()
        val callbacks = object : RemoteCallbackList<IShizukuServiceConnection>() {
            override fun onCallbackDied(callback: IShizukuServiceConnection?) {
                if (daemon || registeredCallbackCount != 0) {
                    return
                }
                synchronized(this@PrivilegedHostService) {
                    if (userService === this@UserServiceSlot) {
                        destroy()
                        userService = null
                    }
                }
            }
        }
        var binder: IBinder? = null
        private var starting = false
        private val timeout = Runnable {
            if (starting) {
                synchronized(this@PrivilegedHostService) {
                    if (userService === this@UserServiceSlot) {
                        destroy()
                        userService = null
                    }
                }
            }
        }
        private val death = IBinder.DeathRecipient {
            synchronized(this@PrivilegedHostService) {
                if (userService === this@UserServiceSlot) {
                    destroy()
                    userService = null
                }
            }
        }

        fun scheduleTimeout() {
            starting = true
            handler.postDelayed(timeout, PrivilegedHostConstants.USER_SERVICE_START_TIMEOUT_MS)
        }

        fun attachBinder(service: IBinder) {
            handler.removeCallbacks(timeout)
            starting = false
            binder = service
            runCatching { service.linkToDeath(death, 0) }
            broadcastConnected()
        }

        fun broadcastConnected() {
            val service = binder ?: return
            val count = callbacks.beginBroadcast()
            for (index in 0 until count) {
                runCatching { callbacks.getBroadcastItem(index).connected(service) }
            }
            callbacks.finishBroadcast()
        }

        fun destroy() {
            handler.removeCallbacks(timeout)
            val service = binder
            if (service != null) {
                runCatching { service.unlinkToDeath(death, 0) }
                if (service.pingBinder()) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(service.interfaceDescriptor ?: "")
                        service.transact(
                            PrivilegedHostConstants.USER_SERVICE_DESTROY,
                            data,
                            reply,
                            IBinder.FLAG_ONEWAY,
                        )
                    } catch (_: Exception) {
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
            }
            callbacks.kill()
            binder = null
        }
    }

    private class Client(
        val uid: Int,
        val pid: Int,
        val application: IShizukuApplication,
        val apiVersion: Int,
    )

    companion object {
        private const val TAG = "ClipSyncPrivHost"

        @JvmStatic
        fun main(args: Array<String>) {
            val uid = Os.getuid()
            if (uid != 0 && uid != 2000) {
                System.err.println("fatal: uid $uid is not shell or root")
                System.exit(PrivilegedHostConstants.EXIT_FATAL_UID)
                return
            }
            HiddenApiExemptions.unseal()
            HiddenApis.setDdmAppName(PrivilegedHostConstants.HOST_PROCESS_NAME, 0)
            Looper.prepareMainLooper()
            PrivilegedHostService()
            Looper.loop()
        }

        private fun allowedBundle(allowed: Boolean): Bundle {
            val data = Bundle()
            data.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
            return data
        }
    }
}
