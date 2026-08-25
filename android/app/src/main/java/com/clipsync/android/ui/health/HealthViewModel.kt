package com.clipsync.android.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingStore
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
import com.clipsync.android.ui.ConduitSegmentState
import com.clipsync.android.ui.ConduitStatus
import com.clipsync.android.ui.ConduitTestResult
import com.clipsync.android.ui.HealthScreenState
import java.util.UUID
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
    private val mutableState = MutableStateFlow(
        buildHealthScreenState(peer = pairingStore.peer(), clipboard = null, sync = null),
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
        refreshJob = viewModelScope.launch {
            do {
                refreshQueued = false
                refreshOnce()
            } while (refreshQueued)
        }
    }

    private suspend fun refreshOnce() {
        val wiring = capability
        val pass = withContext(probeDispatcher) {
            val peer = pairingStore.peer()
            if (wiring == null) {
                Triple(peer, clipboard.probe(), null)
            } else {
                // One ladder pass feeds both the per-route facts and the headline
                // report; probing twice (probe() then probeAll()) would run every
                // backend's prerequisite checks twice per refresh.
                val reports = clipboard.probeAll()
                val facts = CapabilityFacts(
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
        val facts = if (probedFacts != null && peer != null && wiring?.peerHealth != null) {
            probedFacts.copy(reachability = probeReachability(wiring.peerHealth, peer))
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
            val (outcome, readBack) = withContext(probeDispatcher) {
                val written = wiring.writeCoordinator.writeText(
                    text = token,
                    originEventId = "capability-write-test-${wiring.nowMs()}",
                )
                val back = wiring.foregroundBackend.readText()
                wiring.clearClipboard()
                written to back
            }
            val verified = outcome.result is ClipboardWriteResult.Success &&
                (readBack as? ClipboardReadResult.Success)?.text == token
            val errorCode = (outcome.result as? ClipboardWriteResult.Failure)?.errorCode
                ?: ERROR_WRITE_UNVERIFIED.takeUnless { verified }
            withContext(probeDispatcher) {
                wiring.capabilityStore.recordWriteTest(
                    state = if (verified) CapabilityState.READY else CapabilityState.UNAVAILABLE,
                    errorCode = errorCode,
                    atMs = wiring.nowMs(),
                )
            }
            lastFacts = lastFacts?.copy(
                publicWriteState = wiring.capabilityStore.publicWriteState(),
                publicWriteErrorCode = wiring.capabilityStore.publicWriteErrorCode(),
            )
            testResult = if (verified) {
                ConduitTestResult("写入测试通过（测试文本已清除）", success = true)
            } else {
                ConduitTestResult("写入测试失败：$errorCode", success = false)
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
            val selfTest = ClipboardSelfTest(
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
            testResult = if (verified) {
                ConduitTestResult("后台读取测试通过（测试文本已清除）", success = true)
            } else {
                ConduitTestResult("后台读取测试失败：${result.errorCode ?: "未知原因"}", success = false)
            }
            // Re-probe so the just-verified route surfaces as READY (or the failure code shows).
            refresh()
        }
    }

    fun noteAdbCommandCopied() {
        testResult = ConduitTestResult("已复制 adb 命令；在电脑上执行后回来点「重新探测」", success = true)
        publish(pairingStore.peer())
    }

    fun dismissTestResult() {
        testResult = null
        publish(pairingStore.peer())
    }

    private suspend fun probeReachability(peerHealth: PeerHealthApi, peer: PairedPeer): PeerReachability =
        when (peerHealth.probe(peer)) {
            is PeerHealthOutcome.Reachable -> PeerReachability.REACHABLE
            PeerHealthOutcome.CertificateMismatch -> PeerReachability.CERTIFICATE_MISMATCH
            PeerHealthOutcome.Unreachable -> PeerReachability.UNREACHABLE
        }

    private fun publish(peer: PairedPeer?) {
        mutableState.value =
            buildHealthScreenState(peer, lastClipboardReport, lastSyncHealth, lastFacts)
                .copy(testResult = testResult)
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
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
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
): HealthScreenState {
    val network = networkSegment(peer, sync, facts)
    val state = HealthScreenState(
        localRead = if (facts != null) localReadSegmentFromFacts(facts) else localReadSegment(clipboard),
        localService = localServiceSegment(sync),
        network = network,
        peerWrite = peerWriteSegment(network.status, sync),
        pairedDeviceCount = if (peer != null) 1 else 0,
        pairedPeerName = peer?.displayName,
        localWrite = facts?.let(::localWriteSegmentFromFacts),
        routes = facts?.let(::buildReadRoutes).orEmpty(),
        serviceRunning = sync?.serviceRunning ?: false,
        notificationsEnabled = facts?.notificationsEnabled,
    )
    return applySingleBeckon(state)
}

/**
 * Single-beckon rule (charter §5.6): when several segments need action, only
 * the most upstream one lights up; downstream ones keep their status and
 * actions but stay quiet.
 */
private fun applySingleBeckon(state: HealthScreenState): HealthScreenState {
    val pipeOrder = listOfNotNull(
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
            statusLabel = "降级 · 仅前台",
            detail = "应用在前台时可读取剪贴板；后台读取能力尚未接入。",
            status = ConduitStatus.DEGRADED,
        )
    }
    val mode = readModeTitle(report.readMode)
    return when (report.readState) {
        CapabilityState.READY ->
            if (report.readMode == ClipboardReadMode.FOREGROUND_ONLY) {
                ConduitSegmentState(
                    statusLabel = "降级 · 仅前台",
                    detail = "前台读取可用；后台读取需要额外授权。",
                    status = ConduitStatus.DEGRADED,
                )
            } else {
                ConduitSegmentState(
                    statusLabel = "就绪",
                    detail = "后台读取可用（$mode）。",
                    status = ConduitStatus.READY,
                )
            }
        CapabilityState.DEGRADED -> ConduitSegmentState(
            statusLabel = "降级",
            detail = "读取能力降级（$mode）；部分内容可能需要手动发送。",
            status = ConduitStatus.DEGRADED,
        )
        CapabilityState.UNAVAILABLE -> ConduitSegmentState(
            statusLabel = "不可用",
            detail = "后台读取暂不可用；前台复制与分享面板仍然可用。",
            status = ConduitStatus.UNAVAILABLE,
        )
        CapabilityState.UNKNOWN -> ConduitSegmentState(
            statusLabel = "未探测",
            detail = "读取能力尚未探测。缺信息 ≠ 坏消息。",
            status = ConduitStatus.UNPROBED,
        )
        CapabilityState.NEEDS_USER_ACTION -> ConduitSegmentState(
            statusLabel = "待授权",
            detail = "读取能力需要先完成授权或设置（$mode）。",
            status = ConduitStatus.DEGRADED,
        )
    }
}

private fun localServiceSegment(sync: SyncHealth?): ConduitSegmentState = when {
    sync == null -> ConduitSegmentState(
        statusLabel = "就绪",
        detail = "应用运行正常；后台同步服务将在后续阶段接入。",
        status = ConduitStatus.READY,
    )
    sync.serviceRunning -> ConduitSegmentState(
        statusLabel = "就绪",
        detail = "同步服务运行中。",
        status = ConduitStatus.READY,
        detailLines = listOf(
            "前台服务只负责保持连接与调度，不等于获得剪贴板权限。",
            "通知栏会显示常驻通知；停止服务即移除。",
        ),
    )
    sync.serviceErrorCode != null -> ConduitSegmentState(
        statusLabel = "启动失败",
        detail = "同步服务启动失败（${sync.serviceErrorCode}）；应用在前台时仍可同步。",
        status = ConduitStatus.DEGRADED,
    )
    else -> ConduitSegmentState(
        statusLabel = "未运行",
        detail = "同步服务未运行；应用在前台时仍可同步。",
        status = ConduitStatus.DEGRADED,
        detailLines = listOf(
            "前台服务只负责保持连接与调度，不等于获得剪贴板权限。",
            "启动后会显示一条常驻通知。",
        ),
    )
}

private fun networkSegment(
    peer: PairedPeer?,
    sync: SyncHealth?,
    facts: CapabilityFacts? = null,
): ConduitSegmentState = when {
    peer == null -> ConduitSegmentState(
        statusLabel = "需要你操作",
        detail = "尚未与 Windows 配对。在电脑上打开「剪剪相传」，选择「配对新设备」。",
        status = ConduitStatus.NEEDS_ACTION,
    )
    sync?.connected == true && sync.bluetoothFallback -> ConduitSegmentState(
        statusLabel = "已连接 · 蓝牙备援",
        detail = "IP 路径不可达，正在通过蓝牙与「${peer.displayName}」同步（仅文本，速度较慢）。",
        status = ConduitStatus.READY,
        detailLines = listOf(
            "IP 恢复后自动切回，无需操作。",
            "蓝牙期间复制的图片不会同步，且事后不补传。",
        ),
    )
    sync?.connected == true -> ConduitSegmentState(
        statusLabel = "已连接",
        detail = "与「${peer.displayName}」保持连接。",
        status = ConduitStatus.READY,
    )
    sync?.peerThrottled == true -> ConduitSegmentState(
        statusLabel = "已被对端限流",
        detail = "「${peer.displayName}」检测到本机多次认证失败，已临时限流。约 30 秒后自动重试。",
        status = ConduitStatus.DEGRADED,
        errorDetail = "若持续出现，通常表示配对凭据已失效（例如电脑端撤销或重装后）；重新配对可恢复。",
    )
    facts?.reachability == PeerReachability.REACHABLE -> ConduitSegmentState(
        statusLabel = "对端可达",
        detail = "与「${peer.displayName}」握手成功（固定证书 TLS）；同步通道尚未接入。",
        status = ConduitStatus.READY,
        detailLines = listOf("探测方式：固定证书 TLS 请求 /v1/peer/health。"),
    )
    facts?.reachability == PeerReachability.CERTIFICATE_MISMATCH -> ConduitSegmentState(
        statusLabel = "证书不匹配",
        detail = "已与「${peer.displayName}」配对，但对端出示了不同的证书。",
        status = ConduitStatus.DEGRADED,
        errorDetail = "对端证书与固定指纹不符，连接已被阻止。若非你重装了 Windows 端，请检查网络环境；重新配对可更新指纹。",
    )
    facts?.reachability == PeerReachability.UNREACHABLE -> ConduitSegmentState(
        statusLabel = "已配对 · 不可达",
        detail = "已与「${peer.displayName}」配对，当前探测不可达。",
        status = ConduitStatus.DEGRADED,
        detailLines = listOf(
            "确认两台设备在同一局域网 / VPN，且 Windows 端正在运行。",
            "探测方式：固定证书 TLS 请求 /v1/peer/health。",
        ),
    )
    sync == null -> ConduitSegmentState(
        statusLabel = "已配对 · 未连接",
        detail = "已与「${peer.displayName}」配对；同步通道尚未接入。",
        status = ConduitStatus.DEGRADED,
    )
    else -> ConduitSegmentState(
        statusLabel = "已配对 · 未连接",
        detail = "已与「${peer.displayName}」配对，正在等待连接。",
        status = ConduitStatus.DEGRADED,
    )
}

private fun peerWriteSegment(networkStatus: ConduitStatus, sync: SyncHealth?): ConduitSegmentState {
    if (networkStatus != ConduitStatus.READY) {
        return ConduitSegmentState(
            statusLabel = "未探测",
            detail = "网络接通后才能探测对端写入能力。缺信息 ≠ 坏消息。",
            status = ConduitStatus.UNPROBED,
        )
    }
    return when (sync?.peerWriteState) {
        CapabilityState.READY -> ConduitSegmentState(
            statusLabel = "就绪",
            detail = "对端可以自动写入剪贴板。",
            status = ConduitStatus.READY,
        )
        CapabilityState.DEGRADED -> ConduitSegmentState(
            statusLabel = "降级",
            detail = "对端写入能力降级，部分内容可能需要手动粘贴。",
            status = ConduitStatus.DEGRADED,
        )
        CapabilityState.UNAVAILABLE -> ConduitSegmentState(
            statusLabel = "不可用",
            detail = "对端暂时无法写入剪贴板；内容仍会保存到历史。",
            status = ConduitStatus.UNAVAILABLE,
        )
        CapabilityState.UNKNOWN, null -> ConduitSegmentState(
            statusLabel = "未探测",
            detail = "等待对端上报写入能力。",
            status = ConduitStatus.UNPROBED,
        )
        CapabilityState.NEEDS_USER_ACTION -> ConduitSegmentState(
            statusLabel = "待授权",
            detail = "对端写入能力需要先完成授权或设置。",
            status = ConduitStatus.DEGRADED,
        )
    }
}
