package com.clipsync.android.platform.clipboard.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the system-bar mirroring policy: the read window (the only spec that is
 * focusable, and therefore the only one that can become the insets control
 * target on Android 11+) must request exactly the bar state the front app
 * currently enforces — never the platform default. This is what stops the
 * navigation bar from flashing on every poll tick over immersive fullscreen
 * video (e.g. Bilibili landscape) without disturbing bars in normal apps.
 */
class OverlaySystemBarMirrorTest {
    @Test
    fun `immersive front app - read window requests both bars hidden`() {
        val platform = FakeOverlayPlatform()
        platform.barSample = OverlaySystemBarSample(statusBarHidden = true, navigationBarHidden = true)
        platform.clip = OverlayClipRead.Text("clip")
        val controller = OverlayFocusController(platform)

        controller.readText()

        assertEquals(
            OverlayBarRequest(hideStatusBar = true, hideNavigationBar = true),
            singleReadSpec(platform).hiddenBars,
        )
    }

    @Test
    fun `normal front app - read window explicitly requests visible bars`() {
        val platform = FakeOverlayPlatform()
        platform.barSample = OverlaySystemBarSample(statusBarHidden = false, navigationBarHidden = false)
        platform.clip = OverlayClipRead.Text("clip")
        val controller = OverlayFocusController(platform)

        controller.readText()

        assertEquals(
            OverlayBarRequest(hideStatusBar = false, hideNavigationBar = false),
            singleReadSpec(platform).hiddenBars,
        )
    }

    @Test
    fun `nav-only immersive front app mirrors only the navigation bar`() {
        val platform = FakeOverlayPlatform()
        platform.barSample = OverlaySystemBarSample(statusBarHidden = false, navigationBarHidden = true)
        platform.clip = OverlayClipRead.Text("clip")
        val controller = OverlayFocusController(platform)

        controller.readText()

        assertEquals(
            OverlayBarRequest(hideStatusBar = false, hideNavigationBar = true),
            singleReadSpec(platform).hiddenBars,
        )
    }

    @Test
    fun `unknown sample leaves bar requests untouched`() {
        val platform = FakeOverlayPlatform()
        platform.barSample = OverlaySystemBarSample.UNKNOWN
        platform.clip = OverlayClipRead.Text("clip")
        val controller = OverlayFocusController(platform)

        controller.readText()

        assertEquals(null, singleReadSpec(platform).hiddenBars)
    }

    @Test
    fun `idle specs never carry a bar request`() {
        val platform = FakeOverlayPlatform()
        platform.barSample = OverlaySystemBarSample(statusBarHidden = true, navigationBarHidden = true)
        platform.clip = OverlayClipRead.Text("clip")
        val controller = OverlayFocusController(platform)

        controller.readText()
        controller.releaseFocus()

        val idleSpecs =
            platform.windowHistory.filter { spec ->
                spec.flags and OverlayFocusController.FLAG_NOT_FOCUSABLE != 0
            }
        assertTrue(idleSpecs.isNotEmpty())
        idleSpecs.forEach { spec -> assertEquals(null, spec.hiddenBars) }
    }

    @Test
    fun `bar state is sampled before the focus grab`() {
        val platform = FakeOverlayPlatform()
        platform.barSample = OverlaySystemBarSample(statusBarHidden = true, navigationBarHidden = true)
        platform.clip = OverlayClipRead.Text("clip")
        val controller = OverlayFocusController(platform)

        controller.readText()

        val sampleAt = platform.eventLog.indexOf(FakeOverlayPlatform.EVENT_SAMPLE_BARS)
        val focusGrabAt = platform.eventLog.indexOf(FakeOverlayPlatform.EVENT_READ_FLAGS)
        assertTrue(sampleAt >= 0)
        assertTrue(focusGrabAt >= 0)
        assertTrue(sampleAt < focusGrabAt)
    }

    @Test
    fun `read failure still restores idle without touching bar requests`() {
        val platform = FakeOverlayPlatform()
        platform.barSample = OverlaySystemBarSample(statusBarHidden = true, navigationBarHidden = true)
        platform.throwOnRead = true
        val controller = OverlayFocusController(platform)

        controller.readText()

        assertEquals(FakeOverlayPlatform.EVENT_IDLE_FLAGS, platform.eventLog.last())
        assertEquals(null, platform.windowHistory.last().hiddenBars)
        assertEquals(
            OverlayBarRequest(hideStatusBar = true, hideNavigationBar = true),
            singleReadSpec(platform).hiddenBars,
        )
    }

    private fun singleReadSpec(platform: FakeOverlayPlatform): OverlayWindowSpec =
        platform.windowHistory.single { spec ->
            spec.flags and OverlayFocusController.FLAG_NOT_FOCUSABLE == 0
        }
}
