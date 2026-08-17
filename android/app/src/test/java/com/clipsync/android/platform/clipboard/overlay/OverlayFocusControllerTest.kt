package com.clipsync.android.platform.clipboard.overlay

import com.clipsync.android.platform.clipboard.ClipboardReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OverlayFocusControllerTest {
    @Test
    fun `permission missing returns OVERLAY_PERMISSION_MISSING without touching flags`() {
        val platform = FakeOverlayPlatform()
        platform.overlaysAllowed = false
        platform.clip = OverlayClipRead.Text("secret")
        val controller = OverlayFocusController(platform)

        val result = controller.readText()

        assertEquals(
            ClipboardReadResult.Failure(OverlayFocusController.ERROR_PERMISSION_MISSING),
            result,
        )
        assertEquals(OverlayFocusController.ERROR_PERMISSION_MISSING, controller.lastErrorCode())
        assertTrue(platform.windowHistory.isEmpty())
        assertEquals(0, platform.readCount)
    }

    @Test
    fun `touchable-required ROM is unavailable and never drops FLAG_NOT_TOUCHABLE`() {
        val platform = FakeOverlayPlatform()
        platform.touchableRequired = true
        platform.clip = OverlayClipRead.Text("secret")
        val controller = OverlayFocusController(platform)

        val result = controller.readText()

        assertEquals(
            ClipboardReadResult.Failure(OverlayFocusController.ERROR_TOUCHABLE_REQUIRED),
            result,
        )
        assertEquals(OverlayFocusController.ERROR_TOUCHABLE_REQUIRED, controller.lastErrorCode())
        assertTrue(controller.requiresTouchableWindowToRead())
        assertTrue(platform.neverDroppedTouchable())
        assertTrue(platform.snapshotsWithoutTouchable().isEmpty())
        assertFalse(platform.eventLog.contains(FakeOverlayPlatform.EVENT_READ_FLAGS))
        assertEquals(0, platform.readCount)
    }

    @Test
    fun `concurrent readText calls are serialized`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("one")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        platform.readEntered = entered
        platform.blockRead = release
        val controller = OverlayFocusController(platform)
        val barrier = CyclicBarrier(2)
        val secondResult = AtomicReference<ClipboardReadResult>()
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)

        val first = Thread {
            barrier.await(5, TimeUnit.SECONDS)
            controller.readText()
        }
        val second = Thread {
            barrier.await(5, TimeUnit.SECONDS)
            secondStarted.countDown()
            secondResult.set(controller.readText())
            secondFinished.countDown()
        }
        first.start()
        second.start()

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        Thread.sleep(40)
        assertEquals(1, platform.maxFocusableDepth.get())
        assertEquals(1, platform.readCount)
        assertFalse(secondFinished.await(30, TimeUnit.MILLISECONDS))

        platform.clip = OverlayClipRead.Text("two")
        release.countDown()
        first.join(2_000)
        second.join(2_000)

        assertEquals(ClipboardReadResult.Success("two"), secondResult.get())
        assertEquals(1, platform.maxFocusableDepth.get())
        assertSerializedFocusCycles(platform.eventLog)
        assertIdleFlags(platform.currentWindow())
    }

    @Test
    fun `focus flag is restored after a normal read`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("hello")
        val controller = OverlayFocusController(platform)

        val result = controller.readText()

        assertEquals(ClipboardReadResult.Success("hello"), result)
        assertTrue(platform.eventLog.contains(FakeOverlayPlatform.EVENT_READ_FLAGS))
        assertTrue(platform.eventLog.contains(FakeOverlayPlatform.EVENT_READ))
        assertEquals(FakeOverlayPlatform.EVENT_IDLE_FLAGS, platform.eventLog.last())
        assertIdleFlags(platform.currentWindow())
        assertTrue(platform.neverDroppedTouchable())
        val readSpec = platform.windowHistory.first { spec ->
            spec.flags and OverlayFocusController.FLAG_NOT_FOCUSABLE == 0
        }
        assertEquals(OverlayFocusController.FLAG_NOT_TOUCHABLE, readSpec.flags)
        assertEquals(1, readSpec.widthPx)
        assertEquals(1, readSpec.heightPx)
        assertEquals(0f, readSpec.alpha, 0f)
        assertEquals(OverlayFocusController.TYPE_APPLICATION_OVERLAY, readSpec.type)
    }

    @Test
    fun `focus flag is restored after a thrown read`() {
        val platform = FakeOverlayPlatform()
        platform.throwOnRead = true
        val controller = OverlayFocusController(platform)

        val result = controller.readText()

        assertEquals(
            ClipboardReadResult.Failure(OverlayFocusController.ERROR_READ_FAILED),
            result,
        )
        assertEquals(OverlayFocusController.ERROR_READ_FAILED, controller.lastErrorCode())
        assertTrue(platform.eventLog.contains(FakeOverlayPlatform.EVENT_READ_FLAGS))
        assertEquals(FakeOverlayPlatform.EVENT_IDLE_FLAGS, platform.eventLog.last())
        assertIdleFlags(platform.currentWindow())
        assertTrue(platform.neverDroppedTouchable())
    }

    @Test
    fun `idle window is 1x1 alpha 0 with both safety flags`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("x")
        val controller = OverlayFocusController(platform)

        controller.readText()

        val idle = platform.windowHistory.first()
        assertEquals(1, idle.widthPx)
        assertEquals(1, idle.heightPx)
        assertEquals(0f, idle.alpha, 0f)
        assertEquals(
            OverlayFocusController.FLAG_NOT_FOCUSABLE or OverlayFocusController.FLAG_NOT_TOUCHABLE,
            idle.flags,
        )
        assertEquals(OverlayFocusController.TYPE_APPLICATION_OVERLAY, idle.type)
    }

    @Test
    fun `empty then text retries up to three times with 25 to 50 ms delay`() {
        val platform = FakeOverlayPlatform()
        platform.readResults.addLast(OverlayClipRead.Empty)
        platform.readResults.addLast(OverlayClipRead.Empty)
        platform.readResults.addLast(OverlayClipRead.Text("third"))
        val controller = OverlayFocusController(platform, retryDelayMillis = 40L)

        val result = controller.readText()

        assertEquals(ClipboardReadResult.Success("third"), result)
        assertEquals(3, platform.readCount)
        assertEquals(listOf(40L, 40L), platform.delayCalls)
        platform.delayCalls.forEach { delay ->
            assertTrue(delay in OverlayFocusController.MIN_RETRY_DELAY_MS..OverlayFocusController.MAX_RETRY_DELAY_MS)
        }
        assertIdleFlags(platform.currentWindow())
    }

    @Test
    fun `releaseFocus restores idle flags`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("held")
        val controller = OverlayFocusController(platform)
        controller.readText()

        controller.releaseFocus()

        assertIdleFlags(platform.currentWindow())
        assertTrue(platform.neverDroppedTouchable())
    }

    @Test
    fun `detach removes the window and is idempotent`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("held")
        val controller = OverlayFocusController(platform)
        controller.readText()
        assertNotNull(platform.currentWindow())

        controller.detach()
        controller.detach()

        assertEquals(null, platform.currentWindow())
        assertEquals(2, platform.detachCount)
        assertTrue(platform.neverDroppedTouchable())
        assertEquals(FakeOverlayPlatform.EVENT_DETACH, platform.eventLog.last())
    }

    @Test
    fun `detach without an attached window is a no-op besides the seam call`() {
        val platform = FakeOverlayPlatform()
        val controller = OverlayFocusController(platform)

        controller.detach()

        assertEquals(null, platform.currentWindow())
        assertEquals(1, platform.detachCount)
        assertTrue(platform.windowHistory.isEmpty())
    }

    private fun assertIdleFlags(spec: OverlayWindowSpec?) {
        assertNotNull(spec)
        val flags = spec!!.flags
        assertEquals(
            OverlayFocusController.FLAG_NOT_FOCUSABLE or OverlayFocusController.FLAG_NOT_TOUCHABLE,
            flags,
        )
        assertEquals(1, spec.widthPx)
        assertEquals(1, spec.heightPx)
        assertEquals(0f, spec.alpha, 0f)
    }

    private fun assertSerializedFocusCycles(events: List<String>) {
        var focusable = false
        for (event in events) {
            when (event) {
                FakeOverlayPlatform.EVENT_READ_FLAGS -> {
                    assertFalse(focusable)
                    focusable = true
                }
                FakeOverlayPlatform.EVENT_IDLE_FLAGS -> focusable = false
            }
        }
        assertFalse(focusable)
    }
}
