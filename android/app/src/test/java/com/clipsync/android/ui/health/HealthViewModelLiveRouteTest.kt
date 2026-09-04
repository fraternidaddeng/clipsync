package com.clipsync.android.ui.health

import com.clipsync.android.i18n.testString
import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.BackendHealth
import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardCaptureSession
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.ui.ConduitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The conduit follows the ladder as it actually runs: the read segment states the live route and
 * why it fell back, the preferred card offers 重新探测首选路线, and that action climbs back through
 * the capture session (never racing the service's health tick).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelLiveRouteTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = PairingStore(FakeKeyValueStore(), FakeSecretProtector())

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class Harness(
        val model: HealthViewModel,
        val coordinator: ClipboardAccessCoordinator,
        val shizuku: FakeBackgroundClipboardBackend,
    )

    /** A degraded ladder under a running session: privileged READY at start, then FAILED. */
    private fun degradedHarness(): Harness {
        val shizuku =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                backendHealth = BackendHealth(BackendHealthState.FAILED, 50L, "PRIV_HOST_USERSERVICE_DEAD"),
            )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val coordinator = ClipboardAccessCoordinator(listOf(shizuku, foreground), nowEpochMillis = { 1L })
        val session = ClipboardCaptureSession(coordinator = coordinator, onChanged = { })
        session.acquire(ClipboardCaptureSession.Owner.FOREGROUND_SERVICE)
        session.checkHealth()
        shizuku.backendHealth = BackendHealth(BackendHealthState.HEALTHY, 60L)
        val model =
            HealthViewModel(
                pairingStore = store,
                clipboard = coordinator,
                probeDispatcher = dispatcher,
                capability =
                    CapabilityWiring(
                        routeProbes =
                            object : RouteProbes {
                                override fun probe() = RoutePrerequisites()
                            },
                        capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore()),
                        writeCoordinator = ClipboardWriteCoordinator(publicWriter = FakeClipboardWriter()),
                        foregroundBackend = foreground,
                        clearClipboard = { },
                        captureSession = session,
                        formatClock = { "09:15" },
                    ),
            )
        return Harness(model, coordinator, shizuku)
    }

    @Test
    fun `the read segment states the route that actually runs and when it fell back`() {
        val harness = degradedHarness()
        val read = harness.model.state.value.localRead

        assertEquals(ConduitStatus.DEGRADED, read.status)
        assertEquals(
            "当前读取路线：前台/手动。首选 特权直读 已于 09:15 因通道故障降级（与特权通道的连接已断开）。",
            read.detail.testString(),
        )
        val routes = harness.model.state.value.routes
        val privileged = routes.first { it.mode == ClipboardReadMode.SHIZUKU_EVENT }
        assertEquals(RouteActionId.RECOVER_PREFERRED, privileged.recoverAction)
    }

    @Test
    fun `recoverPreferredRoute climbs back through the session and states the outcome`() {
        val harness = degradedHarness()

        harness.model.recoverPreferredRoute()

        val state = harness.model.state.value
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, harness.coordinator.state.activeReadMode)
        assertEquals("已切回首选路线：特权直读", state.testResult?.label?.testString())
        assertEquals("当前读取路线：特权直读（首选）。", state.localRead.detail.testString())
        assertNull(state.routes.first { it.mode == ClipboardReadMode.SHIZUKU_EVENT }.recoverAction)
    }

    @Test
    fun `recoverPreferredRoute leaves a still-dead preferred route alone and says so`() {
        val harness = degradedHarness()
        harness.shizuku.report =
            FakeBackgroundClipboardBackend.capabilityReport(
                ClipboardReadMode.SHIZUKU_EVENT,
                CapabilityState.UNAVAILABLE,
                errorCode = "PRIV_HOST_USERSERVICE_DEAD",
            )

        harness.model.recoverPreferredRoute()

        val result = harness.model.state.value.testResult
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, harness.coordinator.state.activeReadMode)
        assertEquals(
            "首选路线 特权直读 仍未就绪（PRIV_HOST_USERSERVICE_DEAD）；当前路线继续运行",
            result?.label?.testString(),
        )
        // Not ready is a fact, not an error: the strip must not turn red.
        assertEquals(true, result?.success)
    }
}
