package com.clipsync.android.ui.prefs

import com.clipsync.android.i18n.testString
import com.clipsync.android.platform.clipboard.CaptureGate
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.sync.CaptureTallySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fact lines under the preference switches: what the system actually does versus what the
 * switch says. Pure mapping over [PreferencesUiState] + [PreferencesRuntimeFacts].
 */
class PreferencesStatusTest {
    /** Everything healthy and running; tests derive their cases with copy(). */
    private val healthy =
        PreferencesRuntimeFacts(
            serviceRunning = true,
            connected = true,
            serviceStartErrorCode = null,
            activeReadMode = ClipboardReadMode.SHIZUKU_EVENT,
            captureRunning = true,
            captureGate = CaptureGate.OPEN,
            tally = CaptureTallySnapshot(captured = 3),
            systemNotificationsEnabled = true,
        )

    private fun runtime(
        serviceRunning: Boolean = true,
        connected: Boolean = true,
        startError: String? = null,
    ) = healthy.copy(serviceRunning = serviceRunning, connected = connected, serviceStartErrorCode = startError)

    private fun capture(
        active: ClipboardReadMode? = ClipboardReadMode.SHIZUKU_EVENT,
        captureRunning: Boolean = true,
        gate: CaptureGate = CaptureGate.OPEN,
    ) = healthy.copy(activeReadMode = active, captureRunning = captureRunning, captureGate = gate)

    @Test
    fun `without runtime facts every line stays empty`() {
        assertEquals(PreferencesStatusLines(), preferencesStatusLines(PreferencesUiState()))
    }

    @Test
    fun `the service line follows the service, not the switch`() {
        fun line(
            enabled: Boolean,
            facts: PreferencesRuntimeFacts,
        ) = preferencesStatusLines(PreferencesUiState(serviceEnabled = enabled, runtime = facts)).service!!

        val off = line(false, runtime())
        assertEquals(FactTone.QUIET, off.tone)
        assertEquals("已停止：不后台监听剪贴板，也不与电脑连接。", off.text.testString())

        val connected = line(true, runtime())
        assertEquals(FactTone.FLOW, connected.tone)
        assertEquals("运行中 · 已与电脑连接", connected.text.testString())

        val waiting = line(true, runtime(connected = false))
        assertEquals(FactTone.QUIET, waiting.tone)

        // On but dead: the one case that reaches out in ochre — the user can restart it.
        val reclaimed = line(true, runtime(serviceRunning = false, connected = false))
        assertEquals(FactTone.ACT, reclaimed.tone)
        assertEquals(
            "开关已开启但服务未在运行（可能被系统回收）；到「通路」页点「启动服务」重新拉起",
            reclaimed.text.testString(),
        )

        val refused = line(true, runtime(serviceRunning = false, connected = false, startError = "FGS_START_DENIED"))
        assertEquals(FactTone.ACT, refused.tone)
        assertEquals(
            "未运行：系统拒绝启动（FGS_START_DENIED）；到「通路」页点「启动服务」重试",
            refused.text.testString(),
        )
    }

    @Test
    fun `pause and private lines appear only while on and read as the user's choice`() {
        val idle = preferencesStatusLines(PreferencesUiState(runtime = runtime()))
        assertNull(idle.pauseSync)
        assertNull(idle.privateMode)

        val gated =
            preferencesStatusLines(
                PreferencesUiState(
                    pauseSync = true,
                    privateMode = true,
                    runtime = capture(active = null, captureRunning = false, gate = CaptureGate.SYNC_PAUSED),
                ),
            )
        assertEquals(FactTone.QUIET, gated.pauseSync!!.tone)
        assertEquals("生效中：后台读取已停止，收到的内容只进历史", gated.pauseSync.text.testString())
        assertEquals("生效中：后台读取已停止，本机复制的内容留在本机", gated.privateMode!!.text.testString())
    }

    @Test
    fun `the capture line names the running route and count, or states the pause honestly`() {
        val running = preferencesStatusLines(PreferencesUiState(runtime = runtime())).pauseCapture!!
        assertEquals(FactTone.FLOW, running.tone)
        assertEquals("后台读取运行中 · 当前路线 特权直读 · 本次运行已捕获 3 条", running.text.testString())

        val paused =
            preferencesStatusLines(
                PreferencesUiState(
                    pauseCapture = true,
                    runtime = capture(active = null, captureRunning = false, gate = CaptureGate.CAPTURE_PAUSED),
                ),
            ).pauseCapture!!
        assertEquals("生效中：后台监听已停止，暂停期间不读取剪贴板", paused.text.testString())

        val stopped =
            preferencesStatusLines(PreferencesUiState(runtime = capture(active = null, captureRunning = false)))
                .pauseCapture!!
        assertEquals(FactTone.QUIET, stopped.tone)
        assertEquals("后台读取未在运行；详见「通路 · 本机读取」", stopped.text.testString())
    }

    @Test
    fun `sensitive and image counters come from the tally and vanish when the switch makes them moot`() {
        val counted =
            preferencesStatusLines(
                PreferencesUiState(
                    imageSync = false,
                    runtime = healthy.copy(tally = CaptureTallySnapshot(skippedSensitive = 2, skippedImageSyncOff = 1)),
                ),
            )
        assertEquals("本次运行已跳过 2 条标记为敏感的内容", counted.skipSensitive!!.text.testString())
        assertEquals("本次运行已有 1 张图片因关闭而未同步", counted.imageSync!!.text.testString())

        val none = preferencesStatusLines(PreferencesUiState(runtime = healthy.copy(tally = CaptureTallySnapshot())))
        assertEquals("本次运行尚未遇到标记为敏感的内容", none.skipSensitive!!.text.testString())
        assertNull(none.imageSync)

        val skipOff = preferencesStatusLines(PreferencesUiState(skipSensitive = false, runtime = runtime()))
        assertNull(skipOff.skipSensitive)
    }

    @Test
    fun `inbox notifications warn in ochre only when the system blocks them`() {
        val unknown = healthy.copy(systemNotificationsEnabled = null)
        val blockedBySystem = healthy.copy(systemNotificationsEnabled = false)
        assertNull(preferencesStatusLines(PreferencesUiState(runtime = healthy)).inboxNotify)
        assertNull(preferencesStatusLines(PreferencesUiState(runtime = unknown)).inboxNotify)

        val blocked = preferencesStatusLines(PreferencesUiState(runtime = blockedBySystem)).inboxNotify!!
        assertEquals(FactTone.ACT, blocked.tone)

        val switchedOff = preferencesStatusLines(PreferencesUiState(inboxNotify = false, runtime = blockedBySystem))
        assertNull(switchedOff.inboxNotify)
    }
}
