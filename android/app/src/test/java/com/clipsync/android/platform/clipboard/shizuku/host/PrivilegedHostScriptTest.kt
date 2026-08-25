package com.clipsync.android.platform.clipboard.shizuku.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedHostScriptTest {
    @Test
    fun `script refuses non-shell uids and never contains a privilege-elevation sequence`() {
        val script = PrivilegedHostScript.render()
        assertTrue(script.contains("id -u"))
        assertTrue(script.contains("exit ${PrivilegedHostConstants.EXIT_FATAL_UID}"))
        assertTrue(script.contains(PrivilegedHostConstants.HOST_PROCESS_NAME))
        assertTrue(script.contains(PrivilegedHostConstants.HOST_MAIN_CLASS))
        assertTrue(script.contains("app_process"))
        assertFalse(script.contains("su "))
        assertFalse(script.contains("pm grant"))
        assertFalse(script.contains("setenforce"))
    }

    @Test
    fun `kill loop matches app_process nice-name without requiring surrounding spaces`() {
        val script = PrivilegedHostScript.render()
        assertTrue(script.contains("*--nice-name=\$PROCESS_NAME*"))
    }

    @Test
    fun `user service command loads ClipSync classes from this apk`() {
        val cmd = PrivilegedHostScript.userServiceCommand(
            apkPath = "/data/app/com.clipsync.android/base.apk",
            token = "token-1",
            packageName = "com.clipsync.android",
            className = "com.clipsync.android.platform.clipboard.shizuku.ClipboardUserService",
            processNameSuffix = "clipsync-clipboard",
            callingUid = 10123,
        )
        assertTrue(cmd.contains("export CLASSPATH='/data/app/com.clipsync.android/base.apk'"))
        assertTrue(cmd.contains("-Djava.class.path='/data/app/com.clipsync.android/base.apk'"))
        assertTrue(cmd.contains(PrivilegedHostConstants.USER_SERVICE_STARTER_CLASS))
        assertTrue(cmd.contains("--class='com.clipsync.android.platform.clipboard.shizuku.ClipboardUserService'"))
        assertTrue(cmd.contains("--nice-name='com.clipsync.android:clipsync-clipboard'"))
        assertTrue(cmd.contains("setsid /system/bin/app_process"))
        assertFalse(cmd.contains("setsid CLASSPATH="))
        assertTrue(cmd.contains("*--nice-name=com.clipsync.android:clipsync-clipboard*"))
        assertFalse(cmd.contains("moe.shizuku.privileged.api"))
    }

    @Test
    fun `host script also kills leftover clipboard user services`() {
        val script = PrivilegedHostScript.render()
        assertTrue(script.contains("US_NAME=\"\$PACKAGE:clipsync-clipboard\""))
        assertTrue(script.contains("*--nice-name=\$US_NAME*"))
        assertTrue(script.contains("clipsync_priv_se*"))
    }

    @Test
    fun `adb command points at this package not the official manager`() {
        assertEquals(
            "adb shell sh /storage/emulated/0/Android/data/com.clipsync.android/start.sh",
            PrivilegedHostScript.adbSdcardCommand(),
        )
    }

    @Test
    fun `destroy opcode matches the Shizuku UserService teardown contract`() {
        assertEquals(16777115, PrivilegedHostConstants.USER_SERVICE_DESTROY)
    }

    @Test
    fun `hidden api unseal is a no-op on the JVM`() {
        HiddenApiExemptions.unseal()
    }
}
