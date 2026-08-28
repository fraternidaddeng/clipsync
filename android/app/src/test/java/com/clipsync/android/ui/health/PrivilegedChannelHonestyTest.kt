package com.clipsync.android.ui.health

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The reported wireless-debugging defect end to end: 特权直读 is authorized (host pings), so the
 * card showed "前提已就绪 · 待实测", yet a real read fails with PRIV_HOST_USERSERVICE_DEAD because
 * the UserService the wireless shell launched is gone. The card must not swallow that failure and
 * revert to the rosy pending-test state — it must keep the honest, actionable failure and point at
 * the PC-side restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegedChannelHonestyTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val pairingStore = PairingStore(FakeKeyValueStore(), FakeSecretProtector())
    private val capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore())

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val authorizedProbes =
        object : RouteProbes {
            override fun probe() =
                RoutePrerequisites(
                    shizukuInstalled = true,
                    shizukuRunning = true,
                    shizukuAuthorized = true,
                )
        }

    private fun privilegedRoute(model: HealthViewModel): ReadRouteUi =
        model.state.value.routes.first { it.id == ReadRouteId.PRIVILEGED }

    private fun buildModel(): HealthViewModel {
        // The privileged host answers, but its UserService child is dead: every real read fails.
        val delegate =
            FakeBackgroundClipboardBackend(
                ClipboardReadMode.SHIZUKU_EVENT,
                readResult = ClipboardReadResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD),
            )
        val adapter =
            ShizukuClipboardBackend(
                probes = authorizedProbes,
                systemVersion = "test",
                delegate = delegate,
                readVerified = { capabilityStore.isReadVerified(ClipboardReadMode.SHIZUKU_EVENT) },
                lastReadFailureCode = {
                    capabilityStore.lastReadFailureCode(ClipboardReadMode.SHIZUKU_EVENT)
                },
            )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        return HealthViewModel(
            pairingStore = pairingStore,
            clipboard = ClipboardAccessCoordinator(listOf(adapter, foreground)),
            syncHealthSource = null,
            probeDispatcher = dispatcher,
            capability =
                CapabilityWiring(
                    routeProbes = authorizedProbes,
                    capabilityStore = capabilityStore,
                    writeCoordinator = ClipboardWriteCoordinator(publicWriter = FakeClipboardWriter()),
                    foregroundBackend = foreground,
                    clearClipboard = {},
                    nowMs = { 1_755_000_000_000 },
                ),
        )
    }

    @Test
    fun `a failed privileged read test keeps the card honest instead of reverting to pending-test`() {
        val model = buildModel()

        // Before the test: prerequisites met, never verified — genuinely just awaiting its check.
        val before = privilegedRoute(model)
        assertEquals(CapabilityState.DEGRADED, before.readState)
        assertEquals(RouteActionId.RUN_READ_TEST, before.readTestAction)

        model.runReadTest(ClipboardReadMode.SHIZUKU_EVENT)

        // After the test hits a dead UserService: the failure sticks on the card.
        val after = privilegedRoute(model)
        assertEquals(CapabilityState.UNAVAILABLE, after.readState)
        assertEquals(ShizukuErrorCodes.USERSERVICE_DEAD, after.errorCode)
        // Honest recovery: restart from a PC, then re-verify — not a dead end.
        assertEquals(RouteActionId.COPY_PRIVILEGED_START_COMMAND, after.nextAction)
        assertEquals(RouteActionId.RUN_READ_TEST, after.readTestAction)
    }

    @Test
    fun `the proven-dead state survives a plain re-probe`() {
        val model = buildModel()
        model.runReadTest(ClipboardReadMode.SHIZUKU_EVENT)
        assertEquals(CapabilityState.UNAVAILABLE, privilegedRoute(model).readState)

        // A later 重新探测 (no new test) must not launder the failure back into 待实测.
        model.refresh()
        val reprobed = privilegedRoute(model)
        assertEquals(CapabilityState.UNAVAILABLE, reprobed.readState)
        assertEquals(ShizukuErrorCodes.USERSERVICE_DEAD, reprobed.errorCode)
    }
}
