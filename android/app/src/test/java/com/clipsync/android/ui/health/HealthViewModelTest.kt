package com.clipsync.android.ui.health

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.ui.ConduitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
