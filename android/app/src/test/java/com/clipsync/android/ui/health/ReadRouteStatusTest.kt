package com.clipsync.android.ui.health

import com.clipsync.android.i18n.testString
import com.clipsync.android.platform.clipboard.CaptureGate
import com.clipsync.android.platform.clipboard.CaptureSessionStatus
import com.clipsync.android.platform.clipboard.ClipboardAccessState
import com.clipsync.android.platform.clipboard.ClipboardCaptureSession
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ReadRouteShortfall
import com.clipsync.android.platform.clipboard.ReadRouteShortfallCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live read-route line: the coordinator's actual state rendered as the conduit words it.
 * Pure mapping — clock formatting is injected, no Android.
 */
class ReadRouteStatusTest {
    private val clock: (Long) -> String = { "14:32" }

    private fun access(
        active: ClipboardReadMode? = ClipboardReadMode.SHIZUKU_EVENT,
        requested: ClipboardReadMode = ClipboardReadMode.SHIZUKU_EVENT,
        shortfall: ReadRouteShortfall? = null,
        lastErrorCode: String? = null,
        lastRecoveryAt: Long? = null,
    ) = ClipboardAccessState(
        requestedReadMode = requested,
        activeReadMode = active,
        autoFallbackAllowed = true,
        lastErrorCode = lastErrorCode,
        lastHealthAtEpochMillis = null,
        shortfall = shortfall,
        lastRecoveryAtEpochMillis = lastRecoveryAt,
    )

    private fun running(gate: CaptureGate = CaptureGate.OPEN) =
        CaptureSessionStatus(
            running = gate == CaptureGate.OPEN,
            owners = setOf(ClipboardCaptureSession.Owner.FOREGROUND_SERVICE),
            gate = gate,
        )

    @Test
    fun `at the preferred rung the line names the route and nothing is degraded`() {
        val status = readRouteStatus(access(), running(), clock)

        assertEquals("当前读取路线：特权直读（首选）。", status.headline.testString())
        assertFalse(status.degradedFromPreferred)
        assertNull(status.stoppedByGate)
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, status.activeMode)
        assertTrue(status.recoverable)
        assertTrue(status.facts.isEmpty())
    }

    @Test
    fun `a health fallback states the running route, the preferred one, the time and the code`() {
        val status =
            readRouteStatus(
                access(
                    active = ClipboardReadMode.OVERLAY_POLLING,
                    shortfall =
                        ReadRouteShortfall(
                            cause = ReadRouteShortfallCause.HEALTH_FALLBACK,
                            fromMode = ClipboardReadMode.SHIZUKU_EVENT,
                            errorCode = "PRIV_HOST_USERSERVICE_DEAD",
                            sinceEpochMillis = 1L,
                        ),
                ),
                running(),
                clock,
            )

        // The code is rendered as the conduit's phrase, never as the machine constant.
        assertEquals(
            "当前读取路线：悬浮窗轮询。首选 特权直读 已于 14:32 因通道故障降级（与特权通道的连接已断开）。",
            status.headline.testString(),
        )
        assertTrue(status.degradedFromPreferred)
        assertTrue(status.facts.any { it.testString().contains("每 5 分钟") })
    }

    @Test
    fun `a shortfall code without a phrase drops the parenthetical instead of printing the constant`() {
        fun headline(
            cause: ReadRouteShortfallCause,
            code: String?,
        ) = readRouteStatus(
            access(
                active = ClipboardReadMode.OVERLAY_POLLING,
                shortfall =
                    ReadRouteShortfall(
                        cause = cause,
                        fromMode = ClipboardReadMode.SHIZUKU_EVENT,
                        errorCode = code,
                        sinceEpochMillis = 1L,
                    ),
            ),
            running(),
            clock,
        ).headline.testString()

        assertEquals(
            "当前读取路线：悬浮窗轮询。首选 特权直读 已于 14:32 因通道故障降级。",
            headline(ReadRouteShortfallCause.HEALTH_FALLBACK, "SOME_FUTURE_CODE"),
        )
        assertEquals(
            "当前读取路线：悬浮窗轮询。首选 特权直读 自 14:32 起未就绪。",
            headline(ReadRouteShortfallCause.PREFERRED_NOT_READY, "CLIPBOARD_READ_NOT_READY"),
        )
        // The observed device case: the host was not started yet.
        assertEquals(
            "当前读取路线：悬浮窗轮询。首选 特权直读 自 14:32 起未就绪（特权通道未运行）。",
            headline(ReadRouteShortfallCause.PREFERRED_NOT_READY, "PRIVILEGED_CHANNEL_OFFLINE"),
        )
    }

    @Test
    fun `a preferred route that was never ready is worded as not ready since, not as a fallback`() {
        val status =
            readRouteStatus(
                access(
                    active = ClipboardReadMode.FOREGROUND_ONLY,
                    shortfall =
                        ReadRouteShortfall(
                            cause = ReadRouteShortfallCause.PREFERRED_NOT_READY,
                            fromMode = ClipboardReadMode.SHIZUKU_EVENT,
                            errorCode = null,
                            sinceEpochMillis = 1L,
                        ),
                ),
                running(),
                clock,
            )

        assertEquals(
            "当前读取路线：前台/手动。首选 特权直读 自 14:32 起未就绪。",
            status.headline.testString(),
        )
        assertTrue(status.degradedFromPreferred)
    }

    @Test
    fun `a closed gate is the user's choice and names the switch`() {
        val status =
            readRouteStatus(
                access(active = null),
                running(CaptureGate.CAPTURE_PAUSED),
                clock,
            )

        assertEquals("后台读取已停止：「暂停自动捕获」开启中，期间不读取剪贴板。", status.headline.testString())
        assertEquals(CaptureGate.CAPTURE_PAUSED, status.stoppedByGate)
        assertFalse(status.degradedFromPreferred)
        assertFalse(status.recoverable)
        assertNull(status.activeMode)
    }

    @Test
    fun `nothing running with the gate open quotes the last error and offers no recovery`() {
        val status =
            readRouteStatus(
                access(active = null, lastErrorCode = "PRIVILEGED_CHANNEL_OFFLINE"),
                CaptureSessionStatus(running = false, owners = emptySet(), gate = CaptureGate.OPEN),
                clock,
            )

        assertEquals(
            "当前没有读取路线在运行（特权通道未运行）；应用在前台时仍可手动发送。",
            status.headline.testString(),
        )
        assertFalse(status.recoverable)

        val unknown =
            readRouteStatus(
                access(active = null, lastErrorCode = "CLIPBOARD_READ_BACKEND_MISSING"),
                CaptureSessionStatus(running = false, owners = emptySet(), gate = CaptureGate.OPEN),
                clock,
            )
        assertEquals(
            "当前没有读取路线在运行（原因未知）；应用在前台时仍可手动发送。",
            unknown.headline.testString(),
        )
    }

    @Test
    fun `a past recovery is stated as a fact line`() {
        val status = readRouteStatus(access(lastRecoveryAt = 5L), running(), clock)

        assertEquals(listOf("最近一次切回更高路线：14:32"), status.facts.map { it.testString() })
    }

    @Test
    fun `without a wired session the coordinator state alone decides recoverability`() {
        assertTrue(readRouteStatus(access(), session = null, formatClock = clock).recoverable)
        assertFalse(readRouteStatus(access(active = null), session = null, formatClock = clock).recoverable)
    }
}
