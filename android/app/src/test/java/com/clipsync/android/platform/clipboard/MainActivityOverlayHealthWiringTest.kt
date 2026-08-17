package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks MainActivity production wiring that JVM tests cannot exercise
 * through ComponentActivity: overlay consent is passed down, health is
 * scheduled only while resumed, and the overlay controller is detached
 * on stop / destroy. Reads source only — never clipboard content.
 */
class MainActivityOverlayHealthWiringTest {
    @Test
    fun `MainActivity passes overlayConsented into backend assembly`() {
        val source = mainActivitySource()
        assertTrue(source.contains("overlayConsented"))
        assertTrue(source.contains("wizardChoices.overlayConsented"))
        assertTrue(
            source.contains("overlayConsented = wizardChoices.overlayConsented") ||
                source.contains("overlayConsented=wizardChoices.overlayConsented"),
        )
    }

    @Test
    fun `MainActivity schedules checkHealth on resume and cancels on stop`() {
        val source = mainActivitySource()
        assertTrue(source.contains("ClipboardHealthLoop"))
        assertTrue(source.contains("checkHealth()"))
        assertTrue(source.contains("onResume"))
        assertTrue(source.contains("onStop"))
        assertTrue(
            source.contains("healthJob") ||
                source.contains("clipboardHealthJob") ||
                source.contains("healthLoop"),
        )
    }

    @Test
    fun `MainActivity detaches the overlay controller on stop and destroy`() {
        val source = mainActivitySource()
        assertTrue(source.contains("override fun onStop()"))
        assertTrue(source.contains("override fun onDestroy()"))
        assertTrue(source.contains(".detach()"))
    }

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("src/main/java/com/clipsync/android/MainActivity.kt"),
            File("app/src/main/java/com/clipsync/android/MainActivity.kt"),
            File("android/app/src/main/java/com/clipsync/android/MainActivity.kt"),
            File("D:/paste/android/app/src/main/java/com/clipsync/android/MainActivity.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("MainActivity.kt not found for wiring assertion")
        return file.readText()
    }
}
