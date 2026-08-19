package com.clipsync.android.platform.clipboard.shizuku.host

/**
 * Allows the `app_process` host and UserService child to call hidden
 * framework members (`ActivityThread`, `IContentProvider`, …).
 *
 * The UserService process name is `package:suffix`, so the runtime treats
 * it as the app and enforces the hidden-API greylist. The same unseal is
 * used by the public LSPosed HiddenApiBypass approach (meta-reflection
 * onto `VMRuntime.setHiddenApiExemptions`). Failures are ignored: uid
 * 0/2000 daemons on some images are already exempt.
 */
internal object HiddenApiExemptions {
    fun unseal() {
        runCatching {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val classArrayType = java.lang.reflect.Array.newInstance(Class::class.java, 0).javaClass
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                classArrayType,
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "getRuntime",
                emptyArray<Class<*>>(),
            ) as java.lang.reflect.Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(Array<String>::class.java),
            ) as java.lang.reflect.Method
            val runtime = getRuntime.invoke(null)
            // Method.invoke is Object...; pass String[] as a single boxed argument.
            setHiddenApiExemptions.invoke(runtime, arrayOf("L") as Any)
        }
    }
}
