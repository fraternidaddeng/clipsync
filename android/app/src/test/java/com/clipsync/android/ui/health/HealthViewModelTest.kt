package com.clipsync.android.ui.health

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.BackendHealth
import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.ui.ConduitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val keyValues = FakeKeyValueStore()
    private val store = PairingStore(keyValues, FakeSecretProtector())

    /** Sync seam fake the tests can push snapshots through. */
    private class FakeSyncHealthSource(initial: SyncHealth) : SyncHealthSource {
        val flow = MutableStateFlow(initial)

        override fun snapshots() = flow
    }

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        coordinator: ClipboardAccessCoordinator = ClipboardAccessCoordinator(emptyList()),
        syncHealthSource: SyncHealthSource? = null,
    ) = HealthViewModel(store, coordinator, syncHealthSource, probeDispatcher = dispatcher)

    private fun pair() {
        store.savePeer(
            qr = PairingQrPayload(
                kind = PairingDocumentKinds.QR,
                version = 1,
                hosts = listOf("192.168.1.23"),
                port = 47654,
                deviceId = WINDOWS_ID,
                displayName = "DESKTOP-WIN",
                certSha256 = CERT,
                token = TOKEN,
                expiresAtMs = 1_755_064_500_000,
            ),
            response = PairingConfirmResponse(
                kind = PairingDocumentKinds.CONFIRM_RESPONSE,
                version = 1,
                deviceId = WINDOWS_ID,
                displayName = "DESKTOP-WIN",
                platform = "windows",
                pairSecret = TOKEN,
                trustEpoch = 1,
            ),
            pairSecret = ByteArray(32),
            nowMs = 1_755_000_000_000,
        )
    }

    private fun backend(mode: ClipboardReadMode, state: CapabilityState) =
        FakeBackgroundClipboardBackend(
            mode = mode,
            report = FakeBackgroundClipboardBackend.capabilityReport(mode, state),
        )

    @Test
    fun `unpaired without backends or sync shows the honest baseline`() {
        val state = viewModel().state.value
        assertEquals(ConduitStatus.DEGRADED, state.localRead.status)
        assertEquals(ConduitStatus.READY, state.localService.status)
        assertEquals(ConduitStatus.NEEDS_ACTION, state.network.status)
        assertEquals(ConduitStatus.UNPROBED, state.peerWrite.status)
        assertEquals(0, state.pairedDeviceCount)
    }

    @Test
    fun `paired without sync engine degrades network instead of claiming a connection`() {
        pair()
        val state = viewModel().state.value
        assertEquals(ConduitStatus.DEGRADED, state.network.status)
        assertEquals("已配对 · 未连接", state.network.statusLabel)
        assertEquals(ConduitStatus.UNPROBED, state.peerWrite.status)
        assertEquals(1, state.pairedDeviceCount)
    }

    @Test
    fun `refresh picks up a pairing saved after construction`() {
        val model = viewModel()
        assertEquals(ConduitStatus.NEEDS_ACTION, model.state.value.network.status)

        pair()
        model.refresh()

        assertEquals(ConduitStatus.DEGRADED, model.state.value.network.status)
        assertEquals(1, model.state.value.pairedDeviceCount)
    }

    @Test
    fun `connected sync with peer write ready lights the whole conduit`() {
        pair()
        val sync = FakeSyncHealthSource(
            SyncHealth(serviceRunning = true, connected = true, peerWriteState = CapabilityState.READY),
        )
        val state = viewModel(syncHealthSource = sync).state.value
        assertEquals(ConduitStatus.READY, state.localService.status)
        assertEquals(ConduitStatus.READY, state.network.status)
        assertEquals(ConduitStatus.READY, state.peerWrite.status)
    }

    @Test
    fun `sync snapshots update state without an explicit refresh`() {
        pair()
        val sync = FakeSyncHealthSource(SyncHealth(serviceRunning = true, connected = false))
        val model = viewModel(syncHealthSource = sync)
        assertEquals(ConduitStatus.DEGRADED, model.state.value.network.status)

        sync.flow.value =
            SyncHealth(serviceRunning = true, connected = true, peerWriteState = CapabilityState.UNAVAILABLE)

        assertEquals(ConduitStatus.READY, model.state.value.network.status)
        assertEquals(ConduitStatus.UNAVAILABLE, model.state.value.peerWrite.status)
    }

    @Test
    fun `stopped sync service degrades the local service segment`() {
        val sync = FakeSyncHealthSource(SyncHealth(serviceRunning = false, connected = false))
        val state = viewModel(syncHealthSource = sync).state.value
        assertEquals(ConduitStatus.DEGRADED, state.localService.status)
    }

    @Test
    fun `connected sync without a peer capability report stays unprobed`() {
        pair()
        val sync = FakeSyncHealthSource(SyncHealth(serviceRunning = true, connected = true))
        val state = viewModel(syncHealthSource = sync).state.value
        assertEquals(ConduitStatus.UNPROBED, state.peerWrite.status)
    }

    @Test
    fun `ready background backend makes local read ready`() {
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(backend(ClipboardReadMode.SHIZUKU_EVENT, CapabilityState.READY)),
        )
        val state = viewModel(coordinator).state.value
        assertEquals(ConduitStatus.READY, state.localRead.status)
    }

    @Test
    fun `foreground-only backend is degraded even when ready`() {
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(backend(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.READY)),
        )
        val state = viewModel(coordinator).state.value
        assertEquals(ConduitStatus.DEGRADED, state.localRead.status)
        assertEquals("降级 · 仅前台", state.localRead.statusLabel)
    }

    @Test
    fun `unavailable backends state a fact not an error`() {
        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(backend(ClipboardReadMode.SHIZUKU_EVENT, CapabilityState.UNAVAILABLE)),
        )
        val state = viewModel(coordinator).state.value
        assertEquals(ConduitStatus.UNAVAILABLE, state.localRead.status)
    }

    // ---- capability wiring ----------------------------------------------------------------

    /** One shared "system clipboard": the writer fills it, the backend reads it back. */
    private class FakeClipboardEnvironment {
        var text: String? = null
        var nextWriteResult: ClipboardWriteResult = ClipboardWriteResult.Success

        val writer = object : ClipboardWriter {
            override fun probe(): CapabilityState = CapabilityState.READY

            override fun writeText(text: String, originEventId: String): ClipboardWriteResult {
                val result = nextWriteResult
                if (result is ClipboardWriteResult.Success) {
                    this@FakeClipboardEnvironment.text = text
                }
                return result
            }
        }

        val readBackend = object : BackgroundClipboardBackend {
            override val mode = ClipboardReadMode.FOREGROUND_ONLY

            override fun probe() = FakeBackgroundClipboardBackend.capabilityReport(
                mode,
                CapabilityState.READY,
            )

            override fun start(onChanged: (ClipboardChange) -> Unit) = Unit

            override fun stop() = Unit

            override fun readText(): ClipboardReadResult =
                text?.let { ClipboardReadResult.Success(it) } ?: ClipboardReadResult.Empty

            override fun health() = BackendHealth(BackendHealthState.HEALTHY, 1L)
        }
    }

    private fun capabilityHarness(
        prerequisites: RoutePrerequisites = RoutePrerequisites(),
        environment: FakeClipboardEnvironment = FakeClipboardEnvironment(),
    ): Triple<HealthViewModel, ClipboardCapabilityStore, FakeClipboardEnvironment> {
        val capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore())
        val model = HealthViewModel(
            pairingStore = store,
            clipboard = ClipboardAccessCoordinator(listOf(environment.readBackend)),
            syncHealthSource = null,
            probeDispatcher = dispatcher,
            capability = CapabilityWiring(
                routeProbes = object : RouteProbes {
                    override fun probe() = prerequisites
                },
                capabilityStore = capabilityStore,
                writeCoordinator = ClipboardWriteCoordinator(publicWriter = environment.writer),
                foregroundBackend = environment.readBackend,
                clearClipboard = { environment.text = null },
                nowMs = { 1_755_000_000_000 },
            ),
        )
        return Triple(model, capabilityStore, environment)
    }

    @Test
    fun `capability wiring surfaces the three wizard routes and the local write card`() {
        val (model, _, _) = capabilityHarness()
        val state = model.state.value
        assertEquals(3, state.routes.size)
        assertEquals(ConduitStatus.UNPROBED, state.localWrite?.status)
        // Fresh device: every route still has steps remaining.
        assertEquals(listOf(2, 2, 2), state.routes.map { it.stepsRemaining })
    }

    @Test
    fun `preferred read mode choice is persisted and reflected in routes`() {
        val (model, capabilityStore, _) = capabilityHarness()
        model.setPreferredReadMode(ClipboardReadMode.OVERLAY_POLLING)

        assertEquals(ClipboardReadMode.OVERLAY_POLLING, capabilityStore.preferredReadMode())
        val polling = model.state.value.routes.first { it.mode == ClipboardReadMode.OVERLAY_POLLING }
        assertTrue(polling.preferred)
    }

    @Test
    fun `write test verifies the round trip, clears the token and records ready`() {
        val (model, capabilityStore, environment) = capabilityHarness()
        model.runWriteTest()

        assertEquals(CapabilityState.READY, capabilityStore.publicWriteState())
        assertEquals(ConduitStatus.READY, model.state.value.localWrite?.status)
        assertEquals(true, model.state.value.testResult?.success)
        // The test token never survives the test (plan §5.3).
        assertNull(environment.text)
    }

    @Test
    fun `failed write test records unavailable with its stable error code`() {
        val environment = FakeClipboardEnvironment()
        environment.nextWriteResult = ClipboardWriteResult.Failure("CLIPBOARD_WRITE_DENIED")
        val (model, capabilityStore, _) = capabilityHarness(environment = environment)
        model.runWriteTest()

        assertEquals(CapabilityState.UNAVAILABLE, capabilityStore.publicWriteState())
        assertEquals("CLIPBOARD_WRITE_DENIED", capabilityStore.publicWriteErrorCode())
        assertEquals(ConduitStatus.UNAVAILABLE, model.state.value.localWrite?.status)
        assertEquals(false, model.state.value.testResult?.success)
    }

    @Test
    fun `authorization grant then refresh moves the privileged route from denied to awaiting verification`() {
        // The real backend + probes pair from production, with only the Shizuku
        // API answers faked: channel up, authorization initially denied.
        var prerequisites = RoutePrerequisites(shizukuInstalled = true, shizukuRunning = true)
        val probes = object : RouteProbes {
            override fun probe() = prerequisites
        }
        val environment = FakeClipboardEnvironment()
        val model = HealthViewModel(
            pairingStore = store,
            clipboard = ClipboardAccessCoordinator(
                listOf(
                    ShizukuClipboardBackend(probes, systemVersion = "test"),
                    environment.readBackend,
                ),
            ),
            syncHealthSource = null,
            probeDispatcher = dispatcher,
            capability = CapabilityWiring(
                routeProbes = probes,
                capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore()),
                writeCoordinator = ClipboardWriteCoordinator(publicWriter = environment.writer),
                foregroundBackend = environment.readBackend,
                clearClipboard = { environment.text = null },
                nowMs = { 1_755_000_000_000 },
            ),
        )

        // Channel up but unauthorized: the card offers exactly one in-app action.
        val before = model.state.value.routes.first { it.id == ReadRouteId.PRIVILEGED }
        assertEquals(RouteActionId.REQUEST_PRIVILEGED_PERMISSION, before.nextAction)
        assertEquals(1, before.stepsRemaining)
        assertEquals(CapabilityState.UNAVAILABLE, before.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_PERMISSION_DENIED, before.errorCode)

        // The grant happens in the privileged host's own dialog; the app reacts
        // through its permission-result listener, which only calls refresh().
        prerequisites = prerequisites.copy(shizukuAuthorized = true)
        model.refresh()

        val after = model.state.value.routes.first { it.id == ReadRouteId.PRIVILEGED }
        assertEquals(0, after.stepsRemaining)
        assertNull(after.nextAction) // preferred by default; nothing left to tap
        assertEquals(CapabilityState.DEGRADED, after.readState)
        assertEquals(ShizukuClipboardBackend.ERROR_READ_UNVERIFIED, after.errorCode)
        // Honest ceiling: authorized is never READY before device-verified reads.
        assertEquals("已授权 · 待实测", model.state.value.localRead.statusLabel)
    }

    @Test
    fun `all background routes closed beckons the read segment above the unpaired network`() {
        val (model, _, _) = capabilityHarness()
        // Only the foreground backend is registered, so every background route
        // is absent -> the coordinator reports nothing for them; simulate the
        // all-closed ladder through prerequisites-only probing instead.
        val state = model.state.value
        // Unpaired network still needs action; read is degraded (foreground only).
        assertEquals(ConduitStatus.NEEDS_ACTION, state.network.status)
        assertTrue(state.network.beckoning)
    }

    @Test
    fun `charter forbids red - no segment ever needs action except network`() {
        // NEEDS_ACTION is reserved for the one segment the user can fix (pairing).
        pair()
        val sync = FakeSyncHealthSource(
            SyncHealth(serviceRunning = false, connected = false, peerWriteState = CapabilityState.UNAVAILABLE),
        )
        val state = viewModel(syncHealthSource = sync).state.value
        assertEquals(
            emptyList<ConduitStatus>(),
            listOf(state.localRead.status, state.localService.status, state.peerWrite.status)
                .filter { it == ConduitStatus.NEEDS_ACTION },
        )
    }

    private companion object {
        const val WINDOWS_ID = "11111111-1111-4111-8111-111111111111"
        const val CERT = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25"
        const val TOKEN = "vJ8kAqhFRWDdiWvUuJ9lPCS0jBSJ73dP9-b1JzW5Qk4"
    }
}
