package com.clipsync.android.ui.health

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.sync.SyncConnectionState
import com.clipsync.android.sync.SyncTransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * What the conduit needs to know about the sync engine, and nothing more.
 * Network online does not imply the peer can write its clipboard, so the two
 * facts travel separately (product-scope: capabilities are independent).
 */
data class SyncHealth(
    /** The background sync service (or in-process runtime) is alive. */
    val serviceRunning: Boolean,
    /**
     * The 后台同步服务 master switch (`sync.service_enabled`). False means the user turned
     * the service off on purpose — the conduit must state that as a chosen fact, never
     * dress it up as a fault.
     */
    val serviceEnabled: Boolean = true,
    /** An authenticated session with the paired Windows peer is up. */
    val connected: Boolean,
    /** Peer-reported write capability; null until the peer has been probed. */
    val peerWriteState: CapabilityState? = null,
    /** Stable code of the last foreground-service start failure, if any. */
    val serviceErrorCode: String? = null,
    /** The peer is rate-limiting this device after repeated failed authentication. */
    val peerThrottled: Boolean = false,
    /**
     * The live session runs on the bt1 Bluetooth fallback instead of the IP path (ADR 0005).
     * Only meaningful while [connected]; the conduit must state the degraded scope honestly
     * (text only, slower, IP probed for the switch back).
     */
    val bluetoothFallback: Boolean = false,
)

/**
 * Read-side seam for the sync engine, defined by the UI that consumes it.
 * The sync stage implements this (e.g. by adapting `SyncController.state`);
 * until then the app passes null and the conduit states that honestly.
 */
fun interface SyncHealthSource {
    fun snapshots(): Flow<SyncHealth>
}

/**
 * Combines the foreground service's live flows and the 后台同步服务 master switch
 * into conduit snapshots. The switch travels as its own flow — never a per-emission
 * re-read of the store — because flipping it while the service is already stopped
 * (an FGS-denied start left it down, or a stop raced the toggle) changes none of
 * the service's flows: `stop()` is a no-op then, so without the switch's own
 * emission the 本机服务 segment would keep showing the stale 启动失败 fact instead
 * of the chosen 已停用 one.
 */
fun syncHealthFlow(
    serviceRunning: Flow<Boolean>,
    connectionStates: Flow<SyncConnectionState>,
    startErrorCodes: Flow<String?>,
    peerThrottled: Flow<Boolean>,
    serviceEnabled: Flow<Boolean>,
): Flow<SyncHealth> =
    combine(
        serviceRunning,
        connectionStates,
        startErrorCodes,
        peerThrottled,
        serviceEnabled,
    ) { running, connection, startError, throttled, enabled ->
        SyncHealth(
            serviceRunning = running,
            serviceEnabled = enabled,
            connected = connection is SyncConnectionState.Connected,
            serviceErrorCode = startError,
            peerThrottled = throttled,
            // The conduit must state the degraded bt1 path honestly (ADR 0005).
            bluetoothFallback =
                connection is SyncConnectionState.Connected &&
                    connection.transport == SyncTransportKind.BLUETOOTH,
        )
    }
