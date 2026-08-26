package com.clipsync.android.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.pairing.DeviceAccents
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.pairing.PeerClipboardApply
import com.clipsync.android.pairing.PeerHealthApi
import com.clipsync.android.pairing.PeerHealthOutcome
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ClipboardSelfTest
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.ui.ConduitDeviceUi
import com.clipsync.android.ui.ConduitSegmentState
import com.clipsync.android.ui.ConduitStatus
import com.clipsync.android.ui.ConduitTestResult
import com.clipsync.android.ui.HealthScreenState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The capability stack behind the conduit page: route prerequisites, the
 * persisted route choice, the write path and the optional peer reachability
 * probe. All optional — the conduit stays honest with any subset missing.
 */
data class CapabilityWiring(
    val routeProbes: RouteProbes,
    val capabilityStore: ClipboardCapabilityStore,
    val writeCoordinator: ClipboardWriteCoordinator,
    val foregroundBackend: BackgroundClipboardBackend,
    val clearClipboard: () -> Unit,
    val peerHealth: PeerHealthApi? = null,
    /**
     * Whether this app may post notifications right now (POST_NOTIFICATIONS
     * granted and the channel surface not switched off). Probed on every
     * refresh — a denial hides the inbox/recovery notifications silently, so
     * the conduit page must state it. Null = not wired on this build.
     */
    val notificationsEnabled: (() -> Boolean)? = null,
    val nowMs: () -> Long = System::currentTimeMillis,
)

/**
 * Owns the conduit's segments. Facts come from the pairing store (network),
 * the clipboard coordinator's probes (local read), the capability wiring
 * (wizard routes, local write) and the sync engine when one exists (service,
 * peer write). Anything not yet wired shows as degraded or unprobed, never as
 * invented good news.
 */
class HealthViewModel(
    private val pairingStore: PairingStore,
    private val clipboard: ClipboardAccessCoordinator,
    syncHealthSource: SyncHealthSource? = null,
    private val probeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val capability: CapabilityWiring? = null,
    /**
     * Periodic nudge to re-probe peer reachability while a peer is paired, so the conduit's
     * 对端可达 state does not go stale on a page the user keeps open. Mirrors the Windows app's
     * 30s live-refresh timer (App.LiveRefreshInterval). Null disables it; the [factory] wires a
     * real periodic tick, tests inject a finite flow so the behaviour is deterministic.
     */
    reachabilityRefreshTicker: Flow<Unit>? = null,
) : ViewModel() {
    // Peer presence is known synchronously (same pattern as PairingViewModel);
    // clipboard and sync facts arrive asynchronously via refresh()/snapshots().
    private val mutableState =
        MutableStateFlow(
            buildHealthScreenState(
                peer = pairingStore.peer(),
                clipboard = null,
                sync = null,
                deviceAccent = pairingStore::deviceAccent,
            ),
        )

    val state: StateFlow<HealthScreenState> = mutableState.asStateFlow()

    private var lastClipboardReport: CapabilityReport? = null
    private var lastSyncHealth: SyncHealth? = null
    private var lastFacts: CapabilityFacts? = null
    private var testResult: ConduitTestResult? = null
    private var refreshJob: Job? = null
    private var refreshQueued = false

    init {
        if (syncHealthSource != null) {
            viewModelScope.launch {
                syncHealthSource.snapshots().collect { sync ->
                    lastSyncHealth = sync
                    publish(pairingStore.peer())
                }
            }
        }
        if (reachabilityRefreshTicker != null) {
            viewModelScope.launch {
                reachabilityRefreshTicker.collect {
                    // Re-probe only when there is something to probe: a paired peer and a
                    // reachability probe wired. refresh() itself repeats these guards.
                    if (pairingStore.peer() != null && capability?.peerHealth != null) {
                        refresh()
                    }
                }
            }
        }
        refresh()
    }

    /**
     * Full re-probe: pairing store, capability ladder, route prerequisites and
     * (when wired) peer reachability. Called from init, on every resume and on
     * the user's 重新探测 — a grant observed once is never assumed permanent.
     *
     * Triggers arrive in bursts (a resume, a pairing change and a permission listener can
     * all land on the same beat), so concurrent calls coalesce: while a pass is in flight,
     * further calls mark exactly one trailing pass instead of stacking probe passes. The
     * trailing pass starts after the running one, so the freshest state always gets probed.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) {
            refreshQueued = true
            return
        }
        refreshJob =
            viewModelScope.launch {
                do {
                    refreshQueued = false
                    refreshOnce()
                } while (refreshQueued)
            }
    }

    private suspend fun refreshOnce() {
        val wiring = capability
        val pass =
            withContext(probeDispatcher) {
                val peer = pairingStore.peer()
                if (wiring == null) {
                    Triple(peer, clipboard.probe(), null)
                } else {
                    // One ladder pass feeds both the per-route facts and the headline
                    // report; probing twice (probe() then probeAll()) would run every
                    // backend's prerequisite checks twice per refresh.
                    val reports = clipboard.probeAll()
                    val facts =
                        CapabilityFacts(
                            reports = reports.associateBy { it.readMode },
                            prerequisites = wiring.routeProbes.probe(),
                            preferredReadMode = wiring.capabilityStore.preferredReadMode(),
                            publicWriteState = wiring.capabilityStore.publicWriteState(),
                            publicWriteErrorCode = wiring.capabilityStore.publicWriteErrorCode(),
                            notificationsEnabled = wiring.notificationsEnabled?.invoke(),
                        )
                    Triple(peer, ClipboardAccessCoordinator.mostCapable(reports), facts)
                }
            }
        val (peer, report, probedFacts) = pass
        val facts =
            if (probedFacts != null && peer != null && wiring?.peerHealth != null) {
                val probe = probeReachability(wiring.peerHealth, peer)
                probedFacts.copy(
                    reachability = probe.reachability,
                    peerClipboardApply = probe.clipboardApply,
                )
            } else {
                probedFacts
            }
        lastClipboardReport = report
        lastFacts = facts
        publish(peer)
    }

    /** Persists the wizard's route choice (SharedPrefs) and re-derives the page. */
    fun setPreferredReadMode(mode: ClipboardReadMode) {
        val wiring = capability ?: return
        wiring.capabilityStore.setPreferredReadMode(mode)
        clipboard.requestMode(mode)
        lastFacts = lastFacts?.copy(preferredReadMode = mode)
        publish(pairingStore.peer())
    }

    /**
     * Writes an app-generated random token through the write coordinator,
     * verifies it by reading back, clears it immediately and persists the
     * verified state so 本机写回 shows real, tested capability.
     */
    fun runWriteTest() {
        val wiring = capability ?: return
        viewModelScope.launch {
            val token = "clipsync-test-" + UUID.randomUUID().toString().take(8)
            val (outcome, readBack) =
                withContext(probeDispatcher) {
                    val written =
                        wiring.writeCoordinator.writeText(
                            text = token,
                            originEventId = "capability-write-test-${wiring.nowMs()}",
                        )
                    val back = wiring.foregroundBackend.readText()
                    wiring.clearClipboard()
                    written to back
                }
            val verified =
                outcome.result is ClipboardWriteResult.Success &&
                    (readBack as? ClipboardReadResult.Success)?.text == token
            val errorCode =
                (outcome.result as? ClipboardWriteResult.Failure)?.errorCode
                    ?: ERROR_WRITE_UNVERIFIED.takeUnless { verified }
            withContext(probeDispatcher) {
                wiring.capabilityStore.recordWriteTest(
                    state = if (verified) CapabilityState.READY else CapabilityState.UNAVAILABLE,
                    errorCode = errorCode,
                    atMs = wiring.nowMs(),
                )
            }
            lastFacts =
                lastFacts?.copy(
                    publicWriteState = wiring.capabilityStore.publicWriteState(),
                    publicWriteErrorCode = wiring.capabilityStore.publicWriteErrorCode(),
                )
            testResult =
                if (verified) {
                    ConduitTestResult(UiText.Res(R.string.test_write_passed), success = true)
                } else {
                    ConduitTestResult(
                        UiText.Res(R.string.test_write_failed, errorCode.orEmpty()),
                        success = false,
                    )
                }
            publish(pairingStore.peer())
        }
    }

    /**
     * Device-verified background read test for [mode] (plan §8.3): seed an app-generated token
     * through the write coordinator, read it back through the route's real backend, clear it,
     * and persist the outcome. A pass records READY so the backend's probe stops reporting
     * "授权但待实测" and the route finally claims READY. The user's own clipboard content is
     * never read, stored or uploaded — only the random token round-trips.
     */
    fun runReadTest(mode: ClipboardReadMode) {
        val wiring = capability ?: return
        viewModelScope.launch {
            val backend = clipboard.backend(mode)
            val selfTest =
                ClipboardSelfTest(
                    writeCoordinator = wiring.writeCoordinator,
                    readBackend = { backend },
                    clearClipboard = {
                        wiring.clearClipboard()
                        true
                    },
                )
            val result = withContext(probeDispatcher) { selfTest.runReadTest() }
            val verified = result.passed
            withContext(probeDispatcher) {
                wiring.capabilityStore.recordReadTest(
                    mode = mode,
                    state = if (verified) CapabilityState.READY else CapabilityState.UNAVAILABLE,
                    errorCode = result.errorCode,
                    atMs = wiring.nowMs(),
                )
            }
            testResult =
                if (verified) {
                    ConduitTestResult(UiText.Res(R.string.test_read_passed), success = true)
                } else {
                    ConduitTestResult(
                        UiText.Res(
                            R.string.test_read_failed,
                            result.errorCode ?: UiText.Res(R.string.test_reason_unknown),
                        ),
                        success = false,
                    )
                }
            // Re-probe so the just-verified route surfaces as READY (or the failure code shows).
            refresh()
        }
    }

    fun noteAdbCommandCopied() {
        testResult = ConduitTestResult(UiText.Res(R.string.adb_command_copied), success = true)
        publish(pairingStore.peer())
    }

    /** The 特权直读 start command was copied — tell the user where to run it. */
    fun notePrivilegedStartCommandCopied() {
        testResult = ConduitTestResult(UiText.Res(R.string.privileged_start_command_copied), success = true)
        publish(pairingStore.peer())
    }

    fun dismissTestResult() {
        testResult = null
        publish(pairingStore.peer())
    }

    /**
     * Persists a manual device colour for one conduit device row (P1#14) and
     * republishes; null returns the row to its pairing-order default.
     */
    fun setDeviceAccent(
        deviceId: String,
        slot: Int?,
    ) {
        pairingStore.setDeviceAccent(deviceId, slot)
        publish(pairingStore.peer())
    }

    /** Reachability plus the peer's apply self-report, both from the same health probe. */
    private data class PeerProbe(
        val reachability: PeerReachability,
        val clipboardApply: PeerClipboardApply?,
    )

    private suspend fun probeReachability(
        peerHealth: PeerHealthApi,
        peer: PairedPeer,
    ): PeerProbe =
        when (val outcome = peerHealth.probe(peer)) {
            is PeerHealthOutcome.Reachable ->
                PeerProbe(PeerReachability.REACHABLE, outcome.clipboardApplyText)
            PeerHealthOutcome.CertificateMismatch ->
                PeerProbe(PeerReachability.CERTIFICATE_MISMATCH, clipboardApply = null)
            PeerHealthOutcome.Unreachable ->
                PeerProbe(PeerReachability.UNREACHABLE, clipboardApply = null)
        }

    private fun publish(peer: PairedPeer?) {
        mutableState.value =
            buildHealthScreenState(
                peer,
                lastClipboardReport,
                lastSyncHealth,
                lastFacts,
                deviceAccent = pairingStore::deviceAccent,
            ).copy(testResult = testResult)
    }

    companion object {
        const val ERROR_WRITE_UNVERIFIED = "CLIPBOARD_WRITE_UNVERIFIED"

        /** Matches the Windows app's live-refresh cadence so both ends re-check peers on the same beat. */
        const val REACHABILITY_REFRESH_INTERVAL_MS = 30_000L

        fun factory(
            pairingStore: PairingStore,
            clipboard: ClipboardAccessCoordinator,
            syncHealthSource: SyncHealthSource?,
            capability: CapabilityWiring? = null,
            reachabilityRefreshIntervalMs: Long = REACHABILITY_REFRESH_INTERVAL_MS,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HealthViewModel(
                        pairingStore = pairingStore,
                        clipboard = clipboard,
                        syncHealthSource = syncHealthSource,
                        capability = capability,
                        reachabilityRefreshTicker = periodicTicker(reachabilityRefreshIntervalMs),
                    ) as T
            }

        private fun periodicTicker(intervalMs: Long): Flow<Unit>? =
            if (intervalMs <= 0L) {
                null
            } else {
                flow {
                    while (true) {
                        delay(intervalMs)
                        emit(Unit)
                    }
                }
            }
    }
}

/**
 * Pure mapping from facts to the conduit segments. Charter rules: red never
 * appears for "unavailable" (a fact is not an error), and at most one segment
 * beckons — the most upstream NEEDS_ACTION in pipe order.
 */
internal fun buildHealthScreenState(
    peer: PairedPeer?,
    clipboard: CapabilityReport?,
    sync: SyncHealth?,
    facts: CapabilityFacts? = null,
    deviceAccent: (String) -> Int? = { null },
): HealthScreenState {
    val network = networkSegment(peer, sync, facts)
    val state =
        HealthScreenState(
            localRead = if (facts != null) localReadSegmentFromFacts(facts) else localReadSegment(clipboard),
            localService = localServiceSegment(sync),
            network = network,
            peerWrite = peerWriteSegment(network.status, sync, facts),
            pairedDeviceCount = if (peer != null) 1 else 0,
            pairedPeerName = peer?.displayName,
            pairedDevices = conduitDeviceRows(listOfNotNull(peer), deviceAccent),
            localWrite = facts?.let(::localWriteSegmentFromFacts),
            routes = facts?.let(::buildReadRoutes).orEmpty(),
            serviceRunning = sync?.serviceRunning ?: false,
            notificationsEnabled = facts?.notificationsEnabled,
        )
    return applySingleBeckon(state)
}

/**
 * Maps trusted peers in pairing order to conduit device rows (P1#14): the
 * order supplies each row's default slot, a stored manual override wins.
 */
private fun conduitDeviceRows(
    peers: List<PairedPeer>,
    deviceAccent: (String) -> Int?,
): List<ConduitDeviceUi> =
    peers.mapIndexed { index, peer ->
        val defaultSlot = DeviceAccents.defaultSlot(index)
        ConduitDeviceUi(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            platformLabel = if (peer.platform == "windows") "Windows" else peer.platform,
            accentSlot = deviceAccent(peer.deviceId) ?: defaultSlot,
            defaultSlot = defaultSlot,
        )
    }

/**
 * Single-beckon rule (charter §5.6): when several segments need action, only
 * the most upstream one lights up; downstream ones keep their status and
 * actions but stay quiet.
 */
private fun applySingleBeckon(state: HealthScreenState): HealthScreenState {
    val pipeOrder =
        listOfNotNull(
            state.localRead,
            state.localService,
            state.network,
            state.peerWrite,
            state.localWrite,
        )
    val beckoner = pipeOrder.firstOrNull { it.status == ConduitStatus.NEEDS_ACTION }

    fun ConduitSegmentState.resolve() = copy(beckoning = this === beckoner)
    return state.copy(
        localRead = state.localRead.resolve(),
        localService = state.localService.resolve(),
        network = state.network.resolve(),
        peerWrite = state.peerWrite.resolve(),
        localWrite = state.localWrite?.resolve(),
    )
}

private fun localReadSegment(report: CapabilityReport?): ConduitSegmentState {
    if (report == null) {
        // No background backends are registered on this build yet.
        return ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_degraded_foreground),
            detail = UiText.Res(R.string.read_none_detail),
            status = ConduitStatus.DEGRADED,
        )
    }
    val mode = readModeTitle(report.readMode)
    return when (report.readState) {
        CapabilityState.READY ->
            if (report.readMode == ClipboardReadMode.FOREGROUND_ONLY) {
                ConduitSegmentState(
                    statusLabel = UiText.Res(R.string.status_degraded_foreground),
                    detail = UiText.Res(R.string.read_foreground_detail),
                    status = ConduitStatus.DEGRADED,
                )
            } else {
                ConduitSegmentState(
                    statusLabel = UiText.Res(R.string.status_ready),
                    detail = UiText.Res(R.string.read_ready_detail, mode),
                    status = ConduitStatus.READY,
                )
            }
        CapabilityState.DEGRADED ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_degraded),
                detail = UiText.Res(R.string.read_degraded_detail, mode),
                status = ConduitStatus.DEGRADED,
            )
        CapabilityState.UNAVAILABLE ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_unavailable),
                detail = UiText.Res(R.string.read_unavailable_detail),
                status = ConduitStatus.UNAVAILABLE,
            )
        CapabilityState.UNKNOWN ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_unprobed),
                detail = UiText.Res(R.string.read_unknown_detail),
                status = ConduitStatus.UNPROBED,
            )
        CapabilityState.NEEDS_USER_ACTION ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_needs_auth),
                detail = UiText.Res(R.string.read_needs_auth_detail, mode),
                status = ConduitStatus.DEGRADED,
            )
    }
}

private fun localServiceSegment(sync: SyncHealth?): ConduitSegmentState =
    when {
        sync == null ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_ready),
                detail = UiText.Res(R.string.service_ready_no_sync_detail),
                status = ConduitStatus.READY,
            )
        sync.serviceRunning ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_ready),
                detail = UiText.Res(R.string.service_running_detail),
                status = ConduitStatus.READY,
                detailLines =
                    listOf(
                        UiText.Res(R.string.service_fact_fgs),
                        UiText.Res(R.string.service_fact_notification),
                    ),
            )
        sync.serviceErrorCode != null ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_start_failed),
                detail = UiText.Res(R.string.service_start_failed_detail, sync.serviceErrorCode),
                status = ConduitStatus.DEGRADED,
            )
        else ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_not_running),
                detail = UiText.Res(R.string.service_not_running_detail),
                status = ConduitStatus.DEGRADED,
                detailLines =
                    listOf(
                        UiText.Res(R.string.service_fact_fgs),
                        UiText.Res(R.string.service_fact_start_notification),
                    ),
            )
    }

private fun networkSegment(
    peer: PairedPeer?,
    sync: SyncHealth?,
    facts: CapabilityFacts? = null,
): ConduitSegmentState =
    when {
        peer == null ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_needs_action),
                detail = UiText.Res(R.string.network_unpaired_detail),
                status = ConduitStatus.NEEDS_ACTION,
            )
        sync?.connected == true && sync.bluetoothFallback ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_connected_bt),
                detail = UiText.Res(R.string.network_bt_detail, peer.displayName),
                status = ConduitStatus.READY,
                detailLines =
                    listOf(
                        UiText.Res(R.string.network_bt_fact_switchback),
                        UiText.Res(R.string.network_bt_fact_images),
                    ),
            )
        sync?.connected == true ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_connected),
                detail = UiText.Res(R.string.network_connected_detail, peer.displayName),
                status = ConduitStatus.READY,
            )
        sync?.peerThrottled == true ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_throttled),
                detail = UiText.Res(R.string.network_throttled_detail, peer.displayName),
                status = ConduitStatus.DEGRADED,
                errorDetail = UiText.Res(R.string.network_throttled_error),
            )
        facts?.reachability == PeerReachability.REACHABLE ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_peer_reachable),
                detail = UiText.Res(R.string.network_reachable_detail, peer.displayName),
                status = ConduitStatus.READY,
                detailLines = listOf(UiText.Res(R.string.network_probe_fact)),
            )
        facts?.reachability == PeerReachability.CERTIFICATE_MISMATCH ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_cert_mismatch),
                detail = UiText.Res(R.string.network_cert_mismatch_detail, peer.displayName),
                status = ConduitStatus.DEGRADED,
                errorDetail = UiText.Res(R.string.network_cert_mismatch_error),
            )
        facts?.reachability == PeerReachability.UNREACHABLE ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_paired_unreachable),
                detail = UiText.Res(R.string.network_unreachable_detail, peer.displayName),
                status = ConduitStatus.DEGRADED,
                detailLines =
                    listOf(
                        UiText.Res(R.string.network_unreachable_fact),
                        UiText.Res(R.string.network_probe_fact),
                    ),
            )
        sync == null ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_paired_not_connected),
                detail = UiText.Res(R.string.network_not_connected_detail, peer.displayName),
                status = ConduitStatus.DEGRADED,
            )
        else ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_paired_not_connected),
                detail = UiText.Res(R.string.network_waiting_detail, peer.displayName),
                status = ConduitStatus.DEGRADED,
            )
    }

private fun peerWriteSegment(
    networkStatus: ConduitStatus,
    sync: SyncHealth?,
    facts: CapabilityFacts? = null,
): ConduitSegmentState {
    if (networkStatus != ConduitStatus.READY) {
        return ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_unprobed),
            detail = UiText.Res(R.string.peer_write_unprobed_detail),
            status = ConduitStatus.UNPROBED,
        )
    }
    return when (sync?.peerWriteState) {
        CapabilityState.READY ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_ready),
                detail = UiText.Res(R.string.peer_write_ready_detail),
                status = ConduitStatus.READY,
            )
        CapabilityState.DEGRADED ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_degraded),
                detail = UiText.Res(R.string.peer_write_degraded_detail),
                status = ConduitStatus.DEGRADED,
            )
        CapabilityState.UNAVAILABLE ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_unavailable),
                detail = UiText.Res(R.string.peer_write_unavailable_detail),
                status = ConduitStatus.UNAVAILABLE,
            )
        // No engine-level report: the peer's health self-report is the live source today.
        CapabilityState.UNKNOWN, null -> peerWriteFromHealthReport(facts?.peerClipboardApply)
        CapabilityState.NEEDS_USER_ACTION ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_needs_auth),
                detail = UiText.Res(R.string.peer_write_needs_auth_detail),
                status = ConduitStatus.DEGRADED,
            )
    }
}

/**
 * 对端写入 from the peer's `/v1/peer/health` self-report. The peer states its own posture
 * (自动写入 on/off/paused) and the outcome of its most recent real clipboard write — relayed
 * here with attribution, so a working sync path finally reads as working instead of the
 * eternal 未探测 (manual QA 2026-08-25 defect #3).
 */
private fun peerWriteFromHealthReport(apply: PeerClipboardApply?): ConduitSegmentState {
    val attribution = UiText.Res(R.string.peer_write_attribution)
    return when (apply) {
        PeerClipboardApply.APPLIED ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_verified),
                detail = UiText.Res(R.string.peer_write_applied_detail),
                status = ConduitStatus.READY,
                detailLines = listOf(attribution),
            )
        PeerClipboardApply.UNVERIFIED ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_enabled),
                detail = UiText.Res(R.string.peer_write_unverified_detail),
                status = ConduitStatus.READY,
                detailLines = listOf(attribution),
            )
        PeerClipboardApply.OFF ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_peer_apply_off),
                detail = UiText.Res(R.string.peer_write_off_detail),
                status = ConduitStatus.DEGRADED,
                detailLines = listOf(attribution),
            )
        PeerClipboardApply.PAUSED ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_peer_paused),
                detail = UiText.Res(R.string.peer_write_paused_detail),
                status = ConduitStatus.DEGRADED,
                detailLines = listOf(attribution),
            )
        PeerClipboardApply.FAILED ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_write_failed),
                detail = UiText.Res(R.string.peer_write_failed_detail),
                status = ConduitStatus.DEGRADED,
                errorDetail = UiText.Res(R.string.peer_write_failed_error),
                detailLines = listOf(attribution),
            )
        null ->
            ConduitSegmentState(
                statusLabel = UiText.Res(R.string.status_unprobed),
                detail = UiText.Res(R.string.peer_write_unreported_detail),
                status = ConduitStatus.UNPROBED,
                detailLines = listOf(UiText.Res(R.string.peer_write_unreported_fact)),
            )
    }
}
