package com.clipsync.android.ui.health

import com.clipsync.android.platform.clipboard.CapabilityState
import kotlinx.coroutines.flow.Flow

/**
 * What the conduit needs to know about the sync engine, and nothing more.
 * Network online does not imply the peer can write its clipboard, so the two
 * facts travel separately (product-scope: capabilities are independent).
 */
data class SyncHealth(
    /** The background sync service (or in-process runtime) is alive. */
    val serviceRunning: Boolean,
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
