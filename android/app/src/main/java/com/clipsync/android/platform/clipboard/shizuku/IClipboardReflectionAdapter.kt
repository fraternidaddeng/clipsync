package com.clipsync.android.platform.clipboard.shizuku

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Versioned hidden [android.content.IClipboard] reflection adapter for API 29–35.
 *
 * Official AOSP shapes (verified from IClipboard.aidl):
 * - API 29: get(pkg, userId); set/add(clip/listener, pkg, userId)
 * - API 30–33: get(pkg, attributionTag, userId); set/add(..., pkg, attributionTag, userId);
 *   removePrimaryClipChangedListener(listener) is listener-only
 * - API 34–35: adds deviceId on get/set/add/remove
 *
 * resolve() still falls back across shapes when the preferred arity is missing
 * (MIUI and other OEMs may expose only one family).
 *
 * Isolated here so JVM tests can reflect against fake interfaces; the real
 * system binder is only used from [ClipboardUserService] on device.
 */
class IClipboardReflectionAdapter(
    private val clipboard: Any,
    private val sdkInt: Int,
    private val callingPackage: String,
    private val userId: Int = 0,
    private val attributionTag: String? = null,
    private val deviceId: Int = 0,
    private val clipTextReader: ClipTextReader = ReflectiveClipTextReader,
) {
    val selectedShape: IClipboardApiShape = IClipboardApiShape.forSdk(sdkInt)

    fun getPrimaryClipText(): ClipboardAdapterResult {
        val resolved = resolve(GET_PRIMARY_CLIP, MethodKind.GET) ?: return mismatch()
        return try {
            val raw = resolved.method.invoke(clipboard, *argsForGet(resolved.shape))
            val text = clipTextReader.readItemText(raw)
            when {
                text == null -> ClipboardAdapterResult.Empty
                text.isEmpty() -> ClipboardAdapterResult.Empty
                else -> ClipboardAdapterResult.Text(text)
            }
        } catch (error: Throwable) {
            ClipboardAdapterResult.Failed(mapInvokeError(error))
        }
    }

    fun setPrimaryClip(clip: Any): ClipboardAdapterResult {
        val resolved = resolve(SET_PRIMARY_CLIP, MethodKind.SET) ?: return mismatch()
        return try {
            resolved.method.invoke(clipboard, *argsForSet(resolved.shape, clip))
            ClipboardAdapterResult.Empty
        } catch (error: Throwable) {
            ClipboardAdapterResult.Failed(mapInvokeError(error))
        }
    }

    fun addPrimaryClipChangedListener(listener: Any): ClipboardAdapterResult {
        val resolved = resolve(ADD_LISTENER, MethodKind.LISTENER) ?: return mismatch()
        return invokeListener(resolved, listener)
    }

    fun removePrimaryClipChangedListener(listener: Any): ClipboardAdapterResult {
        val resolved = resolve(REMOVE_LISTENER, MethodKind.REMOVE) ?: return mismatch()
        return try {
            resolved.method.invoke(clipboard, *argsForRemove(resolved, listener))
            ClipboardAdapterResult.Empty
        } catch (error: Throwable) {
            ClipboardAdapterResult.Failed(mapInvokeError(error))
        }
    }

    private fun invokeListener(
        resolved: ResolvedMethod,
        listener: Any,
    ): ClipboardAdapterResult {
        return try {
            val raw = resolved.method.invoke(clipboard, *argsForListener(resolved.shape, listener))
            when (raw) {
                is Boolean -> if (raw) {
                    ClipboardAdapterResult.Empty
                } else {
                    ClipboardAdapterResult.Failed(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD)
                }
                is Int -> if (raw != 0) {
                    ClipboardAdapterResult.Empty
                } else {
                    ClipboardAdapterResult.Failed(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD)
                }
                else -> ClipboardAdapterResult.Empty
            }
        } catch (error: Throwable) {
            ClipboardAdapterResult.Failed(mapInvokeError(error))
        }
    }

    private fun resolve(name: String, kind: MethodKind): ResolvedMethod? {
        val methods = clipboard.javaClass.methods.filter { it.name == name }
        val preferred = methods.firstNotNullOfOrNull { method ->
            if (matches(method, selectedShape, kind)) {
                ResolvedMethod(method, selectedShape)
            } else {
                null
            }
        }
        if (preferred != null) {
            return preferred
        }
        for (shape in IClipboardApiShape.entries) {
            if (shape == selectedShape) continue
            val match = methods.firstOrNull { matches(it, shape, kind) } ?: continue
            return ResolvedMethod(match, shape)
        }
        return null
    }

    private fun argsForGet(shape: IClipboardApiShape): Array<Any?> = when (shape) {
        IClipboardApiShape.PKG_USERID -> arrayOf(callingPackage, userId)
        IClipboardApiShape.PKG_ATTRIBUTION_USERID ->
            arrayOf(callingPackage, attributionTag, userId)
        IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE ->
            arrayOf(callingPackage, attributionTag, userId, deviceId)
    }

    private fun argsForSet(shape: IClipboardApiShape, clip: Any): Array<Any?> = when (shape) {
        IClipboardApiShape.PKG_USERID -> arrayOf(clip, callingPackage, userId)
        IClipboardApiShape.PKG_ATTRIBUTION_USERID ->
            arrayOf(clip, callingPackage, attributionTag, userId)
        IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE ->
            arrayOf(clip, callingPackage, attributionTag, userId, deviceId)
    }

    private fun argsForListener(shape: IClipboardApiShape, listener: Any): Array<Any?> =
        when (shape) {
            IClipboardApiShape.PKG_USERID -> arrayOf(listener, callingPackage, userId)
            IClipboardApiShape.PKG_ATTRIBUTION_USERID ->
                arrayOf(listener, callingPackage, attributionTag, userId)
            IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE ->
                arrayOf(listener, callingPackage, attributionTag, userId, deviceId)
        }

    private fun argsForRemove(resolved: ResolvedMethod, listener: Any): Array<Any?> {
        if (resolved.method.parameterTypes.size == 1) {
            return arrayOf(listener)
        }
        return argsForListener(resolved.shape, listener)
    }

    private fun mismatch(): ClipboardAdapterResult =
        ClipboardAdapterResult.Failed(ShizukuErrorCodes.API_MISMATCH)

    companion object {
        const val GET_PRIMARY_CLIP = "getPrimaryClip"
        const val SET_PRIMARY_CLIP = "setPrimaryClip"
        const val ADD_LISTENER = "addPrimaryClipChangedListener"
        const val REMOVE_LISTENER = "removePrimaryClipChangedListener"

        fun mapInvokeError(error: Throwable): String {
            val cause = (error as? InvocationTargetException)?.cause ?: error
            val name = cause.javaClass.name
            return when {
                name.endsWith("DeadObjectException") ||
                    name.endsWith("RemoteException") -> ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
                cause is NoSuchMethodException -> ShizukuErrorCodes.API_MISMATCH
                else -> ShizukuErrorCodes.API_MISMATCH
            }
        }

        internal fun matches(method: Method, shape: IClipboardApiShape, kind: MethodKind): Boolean {
            val params = method.parameterTypes
            if (kind == MethodKind.REMOVE && params.size == 1) {
                // API 29–33: removePrimaryClipChangedListener(listener) only.
                return !isInt(params[0]) && params[0] != String::class.java
            }
            val expected = shape.paramCount(kind)
            if (params.size != expected) {
                return false
            }
            var index = 0
            if (kind != MethodKind.GET) {
                if (isInt(params[0]) || params[0] == String::class.java) {
                    return false
                }
                index = 1
            }
            if (params[index] != String::class.java) {
                return false
            }
            index += 1
            return when (shape) {
                IClipboardApiShape.PKG_USERID -> isInt(params[index])
                IClipboardApiShape.PKG_ATTRIBUTION_USERID ->
                    params[index] == String::class.java && isInt(params[index + 1])
                IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE ->
                    params[index] == String::class.java &&
                        isInt(params[index + 1]) &&
                        isInt(params[index + 2])
            }
        }

        private fun isInt(type: Class<*>): Boolean =
            type == Int::class.javaPrimitiveType || type == Int::class.java
    }
}

enum class IClipboardApiShape {
    /** AOSP API 29: (pkg, userId). */
    PKG_USERID,

    /** AOSP API 30–33: (pkg, attributionTag, userId). */
    PKG_ATTRIBUTION_USERID,

    /** AOSP API 34–35: (pkg, attributionTag, userId, deviceId). */
    PKG_ATTRIBUTION_USERID_DEVICE,
    ;

    fun paramCount(kind: MethodKind): Int {
        val extra = if (kind == MethodKind.GET) 0 else 1
        return extra + when (this) {
            PKG_USERID -> 2
            PKG_ATTRIBUTION_USERID -> 3
            PKG_ATTRIBUTION_USERID_DEVICE -> 4
        }
    }

    companion object {
        fun forSdk(sdkInt: Int): IClipboardApiShape = when {
            sdkInt >= 34 -> PKG_ATTRIBUTION_USERID_DEVICE
            sdkInt >= 30 -> PKG_ATTRIBUTION_USERID
            else -> PKG_USERID
        }
    }
}

enum class MethodKind {
    GET,
    SET,
    LISTENER,
    REMOVE,
}

sealed interface ClipboardAdapterResult {
    data class Text(val value: String) : ClipboardAdapterResult

    data object Empty : ClipboardAdapterResult

    data class Failed(val errorCode: String) : ClipboardAdapterResult
}

fun interface ClipTextReader {
    fun readItemText(clip: Any?): String?
}

object ReflectiveClipTextReader : ClipTextReader {
    override fun readItemText(clip: Any?): String? {
        if (clip == null) {
            return null
        }
        return try {
            val count = clip.javaClass.getMethod("getItemCount").invoke(clip) as Int
            if (count <= 0) {
                return null
            }
            val item = clip.javaClass
                .getMethod("getItemAt", Int::class.javaPrimitiveType)
                .invoke(clip, 0)
            item.javaClass.getMethod("getText").invoke(item)?.toString()
        } catch (_: Throwable) {
            null
        }
    }
}

private data class ResolvedMethod(
    val method: Method,
    val shape: IClipboardApiShape,
)
