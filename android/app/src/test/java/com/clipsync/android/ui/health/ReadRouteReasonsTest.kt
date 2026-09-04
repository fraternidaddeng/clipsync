package com.clipsync.android.ui.health

import com.clipsync.android.i18n.testString
import com.clipsync.android.platform.clipboard.AdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.OverlayPollingBackend
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.clipsync.android.platform.clipboard.adblog.AdbLogOverlayBackend as AdbLogOverlayRuntime
import com.clipsync.android.platform.clipboard.overlay.OverlayPollingBackend as OverlayPollingRuntime

/**
 * The live route line's parenthetical: every code a rung's probe or a running backend's health
 * can put into the shortfall maps to a phrase in the conduit's vocabulary; anything else maps
 * to nothing, so the machine constant never reaches the screen.
 */
class ReadRouteReasonsTest {
    private fun phrase(code: String?): String? = ReadRouteReasons.phraseFor(code)?.testString()

    @Test
    fun `privileged probe and host codes name the channel condition`() {
        assertEquals("特权通道未安装", phrase(ShizukuClipboardBackend.ERROR_CHANNEL_MISSING))
        assertEquals("特权通道未安装", phrase(ShizukuErrorCodes.NOT_INSTALLED))
        assertEquals("特权通道未运行", phrase(ShizukuClipboardBackend.ERROR_CHANNEL_OFFLINE))
        assertEquals("特权通道未运行", phrase(ShizukuErrorCodes.NOT_RUNNING))
        assertEquals("特权通道未授权", phrase(ShizukuClipboardBackend.ERROR_PERMISSION_DENIED))
        assertEquals("特权通道未授权", phrase(ShizukuErrorCodes.NOT_AUTHORIZED))
        assertEquals("与特权通道的连接已断开", phrase(ShizukuErrorCodes.BINDER_DEAD))
        assertEquals("与特权通道的连接已断开", phrase(ShizukuErrorCodes.USERSERVICE_DEAD))
        assertEquals("系统剪贴板服务无法访问", phrase(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD))
        assertEquals("特权通道版本不兼容", phrase(ShizukuErrorCodes.API_MISMATCH))
    }

    @Test
    fun `every stable privileged code has a phrase`() {
        ShizukuErrorCodes.ALL.forEach { code -> assertNotNull(code, phrase(code)) }
    }

    @Test
    fun `the three unverified codes share one phrase because the line already names the route`() {
        assertEquals("尚未完成实测", phrase(ShizukuClipboardBackend.ERROR_READ_UNVERIFIED))
        assertEquals("尚未完成实测", phrase(AdbLogOverlayBackend.ERROR_SIGNAL_UNVERIFIED))
        assertEquals("尚未完成实测", phrase(OverlayPollingBackend.ERROR_READ_UNVERIFIED))
    }

    @Test
    fun `log-sensing and overlay codes name the permission or system condition`() {
        assertEquals("READ_LOGS 尚未授予", phrase(AdbLogOverlayBackend.ERROR_READ_LOGS_NOT_GRANTED))
        assertEquals("READ_LOGS 尚未授予", phrase(AdbLogOverlayRuntime.ERROR_READ_LOGS_NOT_GRANTED))
        assertEquals("READ_LOGS 授权已失效", phrase(AdbLogOverlayRuntime.ERROR_READ_LOGS_REVOKED))
        assertEquals("近期未收到复制信号", phrase(AdbLogOverlayRuntime.ERROR_NO_HEALTHY_SIGNAL))
        assertEquals("悬浮窗权限未开启", phrase(OverlayPollingBackend.ERROR_OVERLAY_MISSING))
        assertEquals("悬浮窗权限未开启", phrase(AdbLogOverlayBackend.ERROR_OVERLAY_MISSING))
        assertEquals("悬浮窗权限未开启", phrase(OverlayPollingRuntime.ERROR_PERMISSION_MISSING))
        assertEquals("此系统不允许悬浮窗读取剪贴板", phrase(OverlayPollingRuntime.ERROR_TOUCHABLE_REQUIRED))
        assertEquals("屏幕未亮起", phrase(OverlayPollingRuntime.ERROR_SCREEN_NOT_INTERACTIVE))
        assertEquals("电池优化未放行", phrase(OverlayPollingBackend.ERROR_BATTERY_RESTRICTED))
    }

    @Test
    fun `unknown and absent codes map to nothing`() {
        assertNull(phrase(null))
        assertNull(phrase("CLIPBOARD_READ_NOT_READY"))
        assertNull(phrase("CLIPBOARD_READ_BACKEND_MISSING"))
        assertNull(phrase("SOME_FUTURE_CODE"))
    }
}
