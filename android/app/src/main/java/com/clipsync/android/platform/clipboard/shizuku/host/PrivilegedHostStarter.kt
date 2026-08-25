package com.clipsync.android.platform.clipboard.shizuku.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.UserManager
import java.io.File

/** Writes the host start script next to the app's external files directory. */
internal object PrivilegedHostStarter {
    // World-readable/executable is the whole point: the script is consumed by the adb
    // shell/root user (a different Linux uid) that launches the privileged host. It
    // contains no secrets — just a `pm path`-resolved app_process invocation.
    @SuppressLint("SetWorldReadable")
    fun writeScript(context: Context): File? =
        runCatching {
            scriptDir(context)?.let { dir ->
                val script = File(dir, PrivilegedHostConstants.SCRIPT_FILE_NAME)
                val apk = context.applicationInfo.sourceDir
                val body =
                    PrivilegedHostScript.render() +
                        "# apk hint for operators; the script also resolves pm path\n" +
                        "# --apk=$apk\n"
                script.writeText(body)
                script.setReadable(true, false)
                script.setExecutable(true, false)
                script
            }
        }.getOrNull()

    private fun scriptDir(context: Context): File? {
        val um = context.getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) {
            return null
        }
        return context
            .getExternalFilesDir(null)
            ?.parentFile
            ?.takeIf { it.exists() || it.mkdirs() }
    }

    fun adbCommand(): String = PrivilegedHostScript.adbSdcardCommand()
}
