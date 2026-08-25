package com.clipsync.android.platform.clipboard.shizuku.host

import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.system.Os

/**
 * Reflection over ActivityManager hidden APIs used only by the privileged
 * host (uid 0/2000). That process is not an app, so hidden-API greylist
 * does not apply.
 */
internal object HiddenApis {
    fun getContentProviderExternal(
        name: String,
        userId: Int,
        token: IBinder?,
        tag: String,
    ): Any? {
        val am = activityManager() ?: return null
        val methods = am.javaClass.methods.filter { it.name == "getContentProviderExternal" }
        val holder = runCatching {
            when {
                methods.any { it.parameterTypes.size == 4 } ->
                    methods.first { it.parameterTypes.size == 4 }
                        .invoke(am, name, userId, token, tag)
                methods.any { it.parameterTypes.size == 3 } ->
                    methods.first { it.parameterTypes.size == 3 }
                        .invoke(am, name, userId, token)
                else -> null
            }
        }.getOrNull() ?: return null
        val providerField = holder.javaClass.fields.firstOrNull { it.name == "provider" }
            ?: holder.javaClass.declaredFields.firstOrNull { it.name == "provider" }?.also {
                it.isAccessible = true
            }
        if (providerField != null) {
            return providerField.get(holder)
        }
        return runCatching {
            holder.javaClass.methods.first { it.name == "getProvider" && it.parameterCount == 0 }
                .invoke(holder)
        }.getOrNull()
    }

    fun providerBinder(provider: Any): IBinder? {
        return runCatching {
            provider.javaClass.methods.first { it.name == "asBinder" && it.parameterCount == 0 }
                .invoke(provider) as? IBinder
        }.getOrNull()
    }

    fun removeContentProviderExternal(name: String, token: IBinder?) {
        val am = activityManager() ?: return
        runCatching {
            am.javaClass.methods.first { it.name == "removeContentProviderExternal" }
                .invoke(am, name, token)
        }
    }

    fun callProvider(
        provider: Any,
        authority: String,
        method: String,
        extras: Bundle,
    ): Bundle? {
        val methods = provider.javaClass.methods.filter { it.name == "call" }
        if (Build.VERSION.SDK_INT >= 31) {
            val source = attributionSource(Os.getuid())
            if (source != null) {
                methods.firstOrNull {
                    it.parameterTypes.size == 5 &&
                        it.parameterTypes[0].name == "android.content.AttributionSource"
                }?.let { match ->
                    invokeBundle(match, provider, source, authority, method, null, extras)
                        ?.let { return it }
                }
            }
        }
        methods.firstOrNull { it.parameterTypes.size == 6 }?.let { match ->
            invokeBundle(
                match,
                provider,
                PrivilegedHostConstants.SHELL_PACKAGE,
                null,
                authority,
                method,
                null,
                extras,
            )?.let { return it }
        }
        methods.firstOrNull {
            it.parameterTypes.size == 5 && it.parameterTypes[0] == String::class.java
        }?.let { match ->
            invokeBundle(
                match,
                provider,
                PrivilegedHostConstants.SHELL_PACKAGE,
                authority,
                method,
                null,
                extras,
            )?.let { return it }
        }
        methods.firstOrNull { it.parameterTypes.size == 4 }?.let { match ->
            invokeBundle(
                match,
                provider,
                PrivilegedHostConstants.SHELL_PACKAGE,
                method,
                null,
                extras,
            )?.let { return it }
        }
        return null
    }

    private fun attributionSource(uid: Int): Any? {
        return runCatching {
            val builderClass = Class.forName("android.content.AttributionSource\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(uid)
            builderClass.getMethod("setPackageName", String::class.java)
                .invoke(builder, PrivilegedHostConstants.SHELL_PACKAGE)
            builderClass.getMethod("build").invoke(builder)
        }.getOrNull()
    }

    private fun invokeBundle(
        method: java.lang.reflect.Method,
        target: Any,
        vararg args: Any?,
    ): Bundle? = runCatching { method.invoke(target, *args) as? Bundle }.getOrNull()

    fun addPowerSaveTempWhitelist(packageName: String, durationMs: Long, userId: Int) {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("getService", String::class.java)
                .invoke(null, "deviceidle") as? IBinder ?: return
            val stub = Class.forName("android.os.IDeviceIdleController\$Stub")
            val controller = stub.getMethod("asInterface", IBinder::class.java).invoke(null, raw)
                ?: return
            val methods = controller.javaClass.methods.filter {
                it.name == "addPowerSaveTempWhitelistApp"
            }
            val four = methods.firstOrNull { it.parameterTypes.size == 4 }
            if (four != null) {
                four.invoke(controller, packageName, durationMs, userId, "shell")
                return
            }
            methods.firstOrNull { it.parameterTypes.size == 3 }
                ?.invoke(controller, packageName, durationMs, userId)
        }
    }

    fun setDdmAppName(name: String, userId: Int) {
        runCatching {
            val cls = Class.forName("android.ddm.DdmHandleAppName")
            cls.getMethod("setAppName", String::class.java, Int::class.javaPrimitiveType)
                .invoke(null, name, userId)
        }
    }

    private fun activityManager(): IInterface? {
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("getService", String::class.java)
                .invoke(null, "activity") as? IBinder ?: return null
            val stub = Class.forName("android.app.IActivityManager\$Stub")
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, raw) as? IInterface
        }.getOrNull()
    }
}
