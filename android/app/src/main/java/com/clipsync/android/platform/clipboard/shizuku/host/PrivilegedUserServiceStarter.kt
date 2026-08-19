package com.clipsync.android.platform.clipboard.shizuku.host

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import moe.shizuku.api.BinderContainer
import rikka.shizuku.ShizukuApiConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Child `app_process` entry. CLASSPATH is ClipSync's own APK, so the
 * UserService class loads directly — no official manager hop.
 */
class PrivilegedUserServiceStarter {
    companion object {
        private const val TAG = "ClipSyncPrivStarter"

        @JvmStatic
        fun main(args: Array<String>) {
            HiddenApiExemptions.unseal()
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper()
            }
            var token: String? = null
            var pkg: String? = null
            var cls: String? = null
            var uid = -1
            var debugName: String? = null
            for (arg in args) {
                when {
                    arg.startsWith("--token=") -> token = arg.substring(8)
                    arg.startsWith("--package=") -> pkg = arg.substring(10)
                    arg.startsWith("--class=") -> cls = arg.substring(8)
                    arg.startsWith("--uid=") -> uid = arg.substring(6).toInt()
                    arg.startsWith("--debug-name=") -> debugName = arg.substring(13)
                }
            }
            if (token == null || pkg == null || cls == null) {
                System.exit(1)
                return
            }
            val service = createService(pkg, cls, uid, debugName)
            if (service == null || !sendBinder(service, token)) {
                System.exit(1)
                return
            }
            Looper.loop()
            System.exit(0)
        }

        private fun createService(
            pkg: String,
            cls: String,
            uid: Int,
            debugName: String?,
        ): IBinder? {
            return try {
                val threadClass = Class.forName("android.app.ActivityThread")
                val activityThread = threadClass.getMethod("systemMain").invoke(null)
                val systemContext = threadClass.getMethod("getSystemContext").invoke(activityThread)
                    as Context
                val userId = if (uid >= 0) uid / 100_000 else 0
                HiddenApis.setDdmAppName(debugName ?: "$pkg:user_service", userId)
                val userHandle = if (uid >= 0) {
                    UserHandle.getUserHandleForUid(uid)
                } else {
                    UserHandle.getUserHandleForUid(android.os.Process.myUid())
                }
                val context = systemContext.javaClass.methods.first {
                    it.name == "createPackageContextAsUser"
                }.invoke(
                    systemContext,
                    pkg,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                    userHandle,
                ) as Context
                val packageInfoField = context.javaClass.getDeclaredField("mPackageInfo")
                packageInfoField.isAccessible = true
                val loadedApk = packageInfoField.get(context)
                val instrumentationClass = Class.forName("android.app.Instrumentation")
                val makeApplication = loadedApk.javaClass.getDeclaredMethod(
                    "makeApplication",
                    Boolean::class.javaPrimitiveType,
                    instrumentationClass,
                )
                val application = makeApplication.invoke(loadedApk, false, null) as Application
                val initial = threadClass.getDeclaredField("mInitialApplication")
                initial.isAccessible = true
                initial.set(activityThread, application)
                val serviceClass = application.classLoader.loadClass(cls)
                val withContext = runCatching {
                    serviceClass.getConstructor(Context::class.java)
                }.getOrNull()
                if (withContext != null) {
                    withContext.newInstance(application) as IBinder
                } else {
                    serviceClass.getDeclaredConstructor().newInstance() as IBinder
                }
            } catch (error: Throwable) {
                Log.w(TAG, "unable to start $pkg/$cls: ${error.javaClass.simpleName}")
                null
            }
        }

        private fun sendBinder(binder: IBinder, token: String): Boolean {
            val name = PrivilegedHostConstants.PROVIDER_AUTHORITY
            val tokenBinder = Binder()
            val provider = HiddenApis.getContentProviderExternal(name, 0, tokenBinder, name)
                ?: return false
            return try {
                val remote = HiddenApis.providerBinder(provider)
                if (remote == null || !remote.pingBinder()) {
                    return false
                }
                val extra = Bundle()
                extra.putParcelable(PrivilegedHostConstants.EXTRA_BINDER, BinderContainer(binder))
                extra.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token)
                val reply = HiddenApis.callProvider(
                    provider,
                    name,
                    PrivilegedHostConstants.METHOD_SEND_USER_SERVICE,
                    extra,
                ) ?: return false
                reply.classLoader = BinderContainer::class.java.classLoader
                @Suppress("DEPRECATION")
                val container = reply.getParcelable<BinderContainer>(
                    PrivilegedHostConstants.EXTRA_BINDER,
                )
                val host = container?.binder
                if (host == null || !host.pingBinder()) {
                    return false
                }
                host.linkToDeath({ System.exit(0) }, 0)
                true
            } catch (error: Exception) {
                Log.e(TAG, "sendUserService: ${error.javaClass.simpleName}")
                false
            } finally {
                HiddenApis.removeContentProviderExternal(name, tokenBinder)
            }
        }
    }
}

/**
 * App-process side of [PrivilegedUserServiceStarter.sendBinder]. Lives in
 * [ClipSyncShizukuProvider] so the child can attach without the official
 * manager package.
 */
internal class UserServiceAttachGate {
    fun attach(binder: IBinder, token: String): IBinder? {
        val options = Bundle().apply {
            putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token)
        }
        if (rikka.shizuku.Shizuku.pingBinder()) {
            return runCatching {
                rikka.shizuku.Shizuku.attachUserService(binder, options)
                rikka.shizuku.Shizuku.getBinder()
            }.getOrNull()
        }
        val latch = CountDownLatch(1)
        var host: IBinder? = null
        val listener = object : rikka.shizuku.Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                try {
                    rikka.shizuku.Shizuku.attachUserService(binder, options)
                    host = rikka.shizuku.Shizuku.getBinder()
                } catch (_: Exception) {
                    host = null
                }
                rikka.shizuku.Shizuku.removeBinderReceivedListener(this)
                latch.countDown()
            }
        }
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(listener)
        return try {
            if (!latch.await(PrivilegedHostConstants.SEND_USER_SERVICE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                rikka.shizuku.Shizuku.removeBinderReceivedListener(listener)
                null
            } else {
                host
            }
        } catch (_: Exception) {
            rikka.shizuku.Shizuku.removeBinderReceivedListener(listener)
            null
        }
    }
}
