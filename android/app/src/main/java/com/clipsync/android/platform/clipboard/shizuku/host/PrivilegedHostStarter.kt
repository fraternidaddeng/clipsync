package com.clipsync.android.platform.clipboard.shizuku.host

import android.content.Context
import android.os.UserManager
import java.io.File

/** Writes the host start script next to the app's external files directory. */
internal object PrivilegedHostStarter {
    fun writeScript(context: Context): File? {
        return runCatching {
            val um = context.getSystemService(UserManager::class.java)
            if (um != null && !um.isUserUnlocked) {
                return null
            }
            val filesDir = context.getExternalFilesDir(null) ?: return null
            val dir = filesDir.parentFile ?: return null
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
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
        }.getOrNull()
    }

    fun adbCommand(): String = PrivilegedHostScript.adbSdcardCommand()
}
