package com.clipsync.android.ui.health

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.pairing.PeerClipboardApply
import com.clipsync.android.pairing.PeerHealthApi
import com.clipsync.android.pairing.PeerHealthOutcome
import com.clipsync.android.platform.clipboard.BackendHealth
import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityReport
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
import com.clipsync.android.ui.HealthScreenState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    fun `peer throttling shows on the network segment until a session authenticates`() {
        pair()
        val sync = FakeSyncHealthSource(
            SyncHealth(serviceRunning = true, connected = false, peerThrottled = true),
        )
        val model = viewModel(syncHealthSource = sync)
        val throttled = model.state.value.network
        assertEquals(ConduitStatus.DEGRADED, throttled.status)
        assertEquals("已被对端限流", throttled.statusLabel)

        // A session that authenticates again ends the episode on every surface.
        sync.flow.value = SyncHealth(serviceRunning = true, connected = true, peerThrottled = false)
        assertEquals(ConduitStatus.READY, model.state.value.network.status)
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
        notificationsEnabled: (() -> Boolean)? = null,
        peerHealth: PeerHealthApi? = null,
        syncHealthSource: SyncHealthSource? = null,
    ): Triple<HealthViewModel, ClipboardCapabilityStore, FakeClipboardEnvironment> {
        val capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore())
        val model = HealthViewModel(
            pairingStore = store,
            clipboard = ClipboardAccessCoordinator(listOf(environment.readBackend)),
            syncHealthSource = syncHealthSource,
            probeDispatcher = dispatcher,
            capability = CapabilityWiring(
                routeProbes = object : RouteProbes {
                    override fun probe() = prerequisites
                },
                capabilityStore = capabilityStore,
                writeCoordinator = ClipboardWriteCoordinator(publicWriter = environment.writer),
                foregroundBackend = environment.readBackend,
                clearClipboard = { environment.text = null },
                peerHealth = peerHealth,
                notificationsEnabled = notificationsEnabled,
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

    // ---- refresh probe economy -------------------------------------------------------------

    /** Foreground-only backend that counts probes so double probing per pass is observable. */
    private class CountingBackend : BackgroundClipboardBackend {
        var probeCount = 0
            private set

        override val mode = ClipboardReadMode.FOREGROUND_ONLY

        override fun probe(): CapabilityReport {
            probeCount++
            return FakeBackgroundClipboardBackend.capabilityReport(mode, CapabilityState.READY)
        }

        override fun start(onChanged: (ClipboardChange) -> Unit) = Unit

        override fun stop() = Unit

        override fun readText(): ClipboardReadResult = ClipboardReadResult.Empty

        override fun health() = BackendHealth(BackendHealthState.HEALTHY, 1L)
    }

    private fun modelWithCapability(
        backend: BackgroundClipboardBackend,
        routeProbes: RouteProbes,
        peerHealth: PeerHealthApi? = null,
    ): HealthViewModel {
        val environment = FakeClipboardEnvironment()
        return HealthViewModel(
            pairingStore = store,
            clipboard = ClipboardAccessCoordinator(listOf(backend)),
            syncHealthSource = null,
            probeDispatcher = dispatcher,
            capability = CapabilityWiring(
                routeProbes = routeProbes,
                capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore()),
                writeCoordinator = ClipboardWriteCoordinator(publicWriter = environment.writer),
                foregroundBackend = environment.readBackend,
                clearClipboard = { environment.text = null },
                peerHealth = peerHealth,
                nowMs = { 1_755_000_000_000 },
            ),
        )
    }

    @Test
    fun `refresh with capability wiring probes each backend exactly once per pass`() {
        val backend = CountingBackend()
        val model = modelWithCapability(
            backend,
            routeProbes = object : RouteProbes {
                override fun probe() = RoutePrerequisites()
            },
        )
        // The init pass runs one ladder probe, not a headline probe plus a ladder probe.
        assertEquals(1, backend.probeCount)

        model.refresh()
        assertEquals(2, backend.probeCount)
    }

    @Test
    fun `a burst of refresh calls during a pass coalesces into one trailing pass`() {
        // Hold the init pass open inside the reachability probe so the burst
        // demonstrably lands while a pass is in flight — the way resume, a pairing
        // change and the permission listeners can all fire on the same beat.
        pair()
        val gate = CompletableDeferred<Unit>()
        var reachabilityProbes = 0
        val peerHealth = object : PeerHealthApi {
            override suspend fun probe(peer: PairedPeer): PeerHealthOutcome {
                reachabilityProbes++
                if (reachabilityProbes == 1) {
                    gate.await()
                }
                return PeerHealthOutcome.Unreachable
            }
        }
        val model = modelWithCapability(
            CountingBackend(),
            routeProbes = object : RouteProbes {
                override fun probe() = RoutePrerequisites()
            },
            peerHealth = peerHealth,
        )
        // The init pass is suspended inside the first reachability probe.
        assertEquals(1, reachabilityProbes)

        model.refresh()
        model.refresh()
        model.refresh()
        gate.complete(Unit)

        // The whole burst collapses into exactly one trailing pass.
        assertEquals(2, reachabilityProbes)
    }

    // ---- notification surface + peer name -------------------------------------------------

    @Test
    fun `notifications off is surfaced as an honest fact and re-probed on refresh`() {
        var enabled = false
        val (model, _, _) = capabilityHarness(notificationsEnabled = { enabled })
        assertEquals(false, model.state.value.notificationsEnabled)

        // The user flips it in system settings; the resume re-probe must pick it up.
        enabled = true
        model.refresh()
        assertEquals(true, model.state.value.notificationsEnabled)
    }

    @Test
    fun `unwired notification probe stays unknown rather than claiming either way`() {
        val (model, _, _) = capabilityHarness()
        assertNull(model.state.value.notificationsEnabled)
    }

    @Test
    fun `no notification fact at all without capability wiring`() {
        assertNull(viewModel().state.value.notificationsEnabled)
    }

    // ---- periodic peer reachability polling (mirrors Windows live refresh) ----------------

    /** Records how many reachability probes ran so the ticker's effect is observable. */
    private class CountingPeerHealth(
        private val outcome: PeerHealthOutcome = PeerHealthOutcome.Unreachable,
    ) : PeerHealthApi {
        var probeCount = 0
            private set

        override suspend fun probe(peer: PairedPeer): PeerHealthOutcome {
            probeCount++
            return outcome
        }
    }

    private fun modelWithReachabilityTicker(
        peerHealth: PeerHealthApi,
        ticker: kotlinx.coroutines.flow.Flow<Unit>,
    ): HealthViewModel {
        val environment = FakeClipboardEnvironment()
        return HealthViewModel(
            pairingStore = store,
            clipboard = ClipboardAccessCoordinator(listOf(environment.readBackend)),
            syncHealthSource = null,
            probeDispatcher = dispatcher,
            capability = CapabilityWiring(
                routeProbes = object : RouteProbes {
                    override fun probe() = RoutePrerequisites()
                },
                capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore()),
                writeCoordinator = ClipboardWriteCoordinator(publicWriter = environment.writer),
                foregroundBackend = environment.readBackend,
                clearClipboard = { environment.text = null },
                peerHealth = peerHealth,
                nowMs = { 1_755_000_000_000 },
            ),
            reachabilityRefreshTicker = ticker,
        )
    }

    @Test
    fun `reachability ticker re-probes the peer while paired`() {
        pair()
        val peerHealth = CountingPeerHealth()
        modelWithReachabilityTicker(peerHealth, flowOf(Unit))
        // The init refresh probes once; the single ticker emission probes again.
        assertEquals(2, peerHealth.probeCount)
    }

    @Test
    fun `reachability ticker stays quiet when unpaired`() {
        val peerHealth = CountingPeerHealth()
        modelWithReachabilityTicker(peerHealth, flowOf(Unit))
        // No peer: neither the init refresh nor the tick has anything to probe.
        assertEquals(0, peerHealth.probeCount)
    }

    // ---- 对端写入 from the peer's health self-report (manual QA 2026-08-25 defect #3) ------

    /** Peer that always answers the same way; enough for the mapping tests. */
    private class FixedPeerHealth(private val outcome: PeerHealthOutcome) : PeerHealthApi {
        override suspend fun probe(peer: PairedPeer): PeerHealthOutcome = outcome
    }

    private fun reachable(apply: PeerClipboardApply?) =
        FixedPeerHealth(PeerHealthOutcome.Reachable(viaHost = "192.168.1.23", clipboardApplyText = apply))

    private fun peerWriteWith(
        peerHealth: PeerHealthApi,
        sync: SyncHealthSource? = null,
    ): HealthScreenState {
        pair()
        val (model, _, _) = capabilityHarness(peerHealth = peerHealth, syncHealthSource = sync)
        return model.state.value
    }

    @Test
    fun `qa defect - working sync no longer leaves peer write on eternal unprobed`() {
        // The exact manual-QA scene: IP sync connected and visibly working, yet 对端写入
        // read 未探测 and the band stayed on 通路部分接通. The peer's health self-report
        // now closes the gap: applied evidence lights the segment.
        val state = peerWriteWith(
            reachable(PeerClipboardApply.APPLIED),
            sync = FakeSyncHealthSource(SyncHealth(serviceRunning = true, connected = true)),
        )
        assertEquals(ConduitStatus.READY, state.network.status)
        assertEquals(ConduitStatus.READY, state.peerWrite.status)
        assertEquals("已验证", state.peerWrite.statusLabel)
        // The claim carries its attribution so the user can see where the fact comes from.
        assertTrue(state.peerWrite.detailLines.any { it.contains("/v1/peer/health") })
    }

    @Test
    fun `peer auto-apply on but nothing applied yet reads ready awaiting evidence`() {
        val segment = peerWriteWith(reachable(PeerClipboardApply.UNVERIFIED)).peerWrite
        assertEquals(ConduitStatus.READY, segment.status)
        assertEquals("已开启", segment.statusLabel)
    }

    @Test
    fun `peer turned auto-apply off - a setting stated as a fact, not a failure`() {
        val segment = peerWriteWith(reachable(PeerClipboardApply.OFF)).peerWrite
        assertEquals(ConduitStatus.DEGRADED, segment.status)
        assertEquals("对端关闭自动写入", segment.statusLabel)
        assertNull(segment.errorDetail)
    }

    @Test
    fun `paused peer degrades the segment while naming the pause`() {
        val segment = peerWriteWith(reachable(PeerClipboardApply.PAUSED)).peerWrite
        assertEquals(ConduitStatus.DEGRADED, segment.status)
        assertEquals("对端已暂停", segment.statusLabel)
    }

    @Test
    fun `failed apply on the peer degrades with the failure spelled out`() {
        val segment = peerWriteWith(reachable(PeerClipboardApply.FAILED)).peerWrite
        assertEquals(ConduitStatus.DEGRADED, segment.status)
        assertEquals("写入失败", segment.statusLabel)
        assertTrue(segment.errorDetail!!.contains("写入失败"))
    }

    @Test
    fun `older peer without the report field stays unprobed and says why`() {
        val segment = peerWriteWith(reachable(apply = null)).peerWrite
        assertEquals(ConduitStatus.UNPROBED, segment.status)
        assertEquals("未探测", segment.statusLabel)
        assertTrue(segment.detail.contains("对端未上报"))
    }

    @Test
    fun `unreachable peer keeps peer write unprobed because the network gates it`() {
        val state = peerWriteWith(FixedPeerHealth(PeerHealthOutcome.Unreachable))
        assertEquals(ConduitStatus.DEGRADED, state.network.status)
        assertEquals(ConduitStatus.UNPROBED, state.peerWrite.status)
    }

    @Test
    fun `engine-level peer write report outranks the health self-report`() {
        val state = peerWriteWith(
            reachable(PeerClipboardApply.APPLIED),
            sync = FakeSyncHealthSource(
                SyncHealth(
                    serviceRunning = true,
                    connected = true,
                    peerWriteState = CapabilityState.UNAVAILABLE,
                ),
            ),
        )
        assertEquals(ConduitStatus.UNAVAILABLE, state.peerWrite.status)
    }

    @Test
    fun `paired peer name reaches the screen state for the device rows`() {
        assertNull(viewModel().state.value.pairedPeerName)

        pair()
        assertEquals("DESKTOP-WIN", viewModel().state.value.pairedPeerName)
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
