package com.clipsync.android.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.ui.ConduitSegmentState
import com.clipsync.android.ui.ConduitStatus
import com.clipsync.android.ui.HealthScreenState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the conduit's four segments. Facts come from three places — the pairing
 * store (network), the clipboard coordinator's probe (local read) and the sync
 * engine when one exists (service, peer write). Anything not yet wired shows
 * as degraded or unprobed, never as invented good news.
 */
class HealthViewModel(
    private val pairingStore: PairingStore,
    private val clipboard: ClipboardAccessCoordinator,
    syncHealthSource: SyncHealthSource? = null,
    private val probeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    // Peer presence is known synchronously (same pattern as PairingViewModel);
    // clipboard and sync facts arrive asynchronously via refresh()/snapshots().
    private val mutableState = MutableStateFlow(
        buildHealthScreenState(peer = pairingStore.peer(), clipboard = null, sync = null),
    )

    val state: StateFlow<HealthScreenState> = mutableState.asStateFlow()

    private var lastClipboardReport: CapabilityReport? = null
    private var lastSyncHealth: SyncHealth? = null

    init {
        if (syncHealthSource != null) {
            viewModelScope.launch {
                syncHealthSource.snapshots().collect { sync ->
                    lastSyncHealth = sync
                    mutableState.value =
                        buildHealthScreenState(pairingStore.peer(), lastClipboardReport, sync)
                }
            }
        }
        refresh()
    }

    /** Re-reads the pairing store and re-probes clipboard capability. */
    fun refresh() {
        viewModelScope.launch {
            val (peer, report) = withContext(probeDispatcher) {
                pairingStore.peer() to clipboard.probe()
            }
            lastClipboardReport = report
            mutableState.value = buildHealthScreenState(peer, report, lastSyncHealth)
        }
    }

    companion object {
        fun factory(
            pairingStore: PairingStore,
            clipboard: ClipboardAccessCoordinator,
            syncHealthSource: SyncHealthSource?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HealthViewModel(pairingStore, clipboard, syncHealthSource) as T
        }
    }
}

/**
 * Pure mapping from facts to the four conduit segments. Charter rule: red
 * never appears here; "unavailable" is a fact and missing info is not bad news.
 */
internal fun buildHealthScreenState(
    peer: PairedPeer?,
    clipboard: CapabilityReport?,
    sync: SyncHealth?,
): HealthScreenState {
    val network = networkSegment(peer, sync)
    return HealthScreenState(
        localRead = localReadSegment(clipboard),
        localService = localServiceSegment(sync),
        network = network,
        peerWrite = peerWriteSegment(network.status, sync),
        pairedDeviceCount = if (peer != null) 1 else 0,
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
    val mode = readModeLabel(report.readMode)
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
    )
    else -> ConduitSegmentState(
        statusLabel = "未运行",
        detail = "同步服务未运行；应用在前台时仍可同步。",
        status = ConduitStatus.DEGRADED,
    )
}

private fun networkSegment(peer: PairedPeer?, sync: SyncHealth?): ConduitSegmentState = when {
    peer == null -> ConduitSegmentState(
        statusLabel = "需要你操作",
        detail = "尚未与 Windows 配对。在电脑上打开「剪剪相传」，选择「配对新设备」。",
        status = ConduitStatus.NEEDS_ACTION,
    )
    sync == null -> ConduitSegmentState(
        statusLabel = "已配对 · 未连接",
        detail = "已与「${peer.displayName}」配对；同步通道尚未接入。",
        status = ConduitStatus.DEGRADED,
    )
    sync.connected -> ConduitSegmentState(
        statusLabel = "已连接",
        detail = "与「${peer.displayName}」保持连接。",
        status = ConduitStatus.READY,
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
    }
}

private fun readModeLabel(mode: ClipboardReadMode): String = when (mode) {
    ClipboardReadMode.SHIZUKU_EVENT -> "Shizuku"
    ClipboardReadMode.ADB_LOG_OVERLAY -> "ADB 日志"
    ClipboardReadMode.OVERLAY_POLLING -> "悬浮窗轮询"
    ClipboardReadMode.FOREGROUND_ONLY -> "仅前台"
}
