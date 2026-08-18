package com.clipsync.android.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the process-ownership wiring that JVM tests cannot exercise through
 * ComponentActivity/Service: the capture stack is built by the process-scoped
 * runtime (not the Activity), both entry points start it, the Activity only
 * reports visibility, and overlay consent still reaches backend assembly.
 * Reads source only - never clipboard content.
 */
class CaptureOwnershipWiringTest {
    @Test
    fun `MainActivity delegates capture to the process runtime`() {
        val source = source("MainActivity.kt")
        assertTrue(source.contains("ClipboardCaptureRuntime.ensureStarted(applicationContext)"))
        assertTrue(source.contains("setActivityVisible(true)"))
        assertTrue(source.contains("setActivityVisible(false)"))
    }

    @Test
    fun `MainActivity no longer owns backends, health loop, or capture teardown`() {
        val source = source("MainActivity.kt")
        assertFalse(source.contains("BackgroundClipboardBackends.build"))
        assertFalse(source.contains("ClipboardHealthLoop"))
        assertFalse(source.contains("access.start"))
        assertFalse(source.contains("clipboardAccess"))
        assertFalse(source.contains("overlayFocus"))
    }

    @Test
    fun `foreground service starts capture for the boot path`() {
        val source = source("service/ClipboardSyncService.kt")
        assertTrue(source.contains("ClipboardCaptureRuntime.ensureStarted(applicationContext)"))
    }

    @Test
    fun `process runtime forwards overlay consent and wizard choices into assembly`() {
        val source = source("capture/ClipboardCaptureRuntime.kt")
        assertTrue(source.contains("overlayConsented = choices.overlayConsented"))
        assertTrue(source.contains("autoFallbackAllowed = choices.autoFallbackAllowed"))
        assertTrue(source.contains("pollIntervalMillis = choices.pollingIntervalMs.toLong()"))
        assertTrue(source.contains("writeFallbackProvider"))
    }

    @Test
    fun `application guards room and loops against the shizuku host process`() {
        val source = source("ClipSyncApplication.kt")
        assertTrue(source.contains("getProcessName() != packageName"))
    }

    @Test
    fun `user service exits when the app callback binder dies`() {
        val source = source("platform/clipboard/shizuku/ClipboardUserService.kt")
        assertTrue(source.contains("linkToDeath"))
        assertTrue(source.contains("onAppCallbackDied"))
        assertTrue(source.contains("exitProcess(0)"))
        // Unregisters the system clipboard listener before exiting.
        val died = source.substringAfter("private fun onAppCallbackDied")
        assertTrue(died.indexOf("unregisterSystemListener()") < died.indexOf("exitProcess(0)"))
        assertTrue(died.indexOf("unregisterSystemListener()") >= 0)
    }

    private fun source(relative: String): String {
        val roots =
            listOf(
                "src/main/java/com/clipsync/android",
                "app/src/main/java/com/clipsync/android",
                "android/app/src/main/java/com/clipsync/android",
                "D:/paste/android/app/src/main/java/com/clipsync/android",
            )
        val file =
            roots.map { File("$it/$relative") }.firstOrNull { it.isFile }
                ?: error("$relative not found for wiring assertion")
        return file.readText()
    }
}
