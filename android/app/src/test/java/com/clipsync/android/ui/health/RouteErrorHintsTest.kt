package com.clipsync.android.ui.health

import com.clipsync.android.i18n.testString
import com.clipsync.android.platform.clipboard.AdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.OverlayPollingBackend
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteErrorHintsTest {
    @Test
    fun `privileged host codes keep their dedicated hint`() {
        assertEquals(
            PrivHostErrorHints.hintFor(ShizukuClipboardBackend.ERROR_CHANNEL_OFFLINE),
            routeErrorHint(ShizukuClipboardBackend.ERROR_CHANNEL_OFFLINE),
        )
    }

    @Test
    fun `overlay and log-sensing codes get the live-route phrase instead of standing alone`() {
        assertEquals("悬浮窗权限未开启", routeErrorHint(OverlayPollingBackend.ERROR_OVERLAY_MISSING)?.testString())
        assertEquals("READ_LOGS 尚未授予", routeErrorHint(AdbLogOverlayBackend.ERROR_READ_LOGS_NOT_GRANTED)?.testString())
        assertNotNull(routeErrorHint(OverlayPollingBackend.ERROR_OVERLAY_MISSING))
    }

    @Test
    fun `unknown codes stay silent so the raw anchor is the only line`() {
        assertNull(routeErrorHint("SOME_FUTURE_CODE"))
    }
}
