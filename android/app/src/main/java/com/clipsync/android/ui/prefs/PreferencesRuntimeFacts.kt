package com.clipsync.android.ui.prefs

import com.clipsync.android.platform.clipboard.CaptureGate
import com.clipsync.android.platform.clipboard.CaptureSessionStatus
import com.clipsync.android.platform.clipboard.ClipboardAccessState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.sync.CaptureStack
import com.clipsync.android.sync.CaptureTallySnapshot
import com.clipsync.android.sync.ClipboardSyncService
import com.clipsync.android.sync.InboxApplyOutcomes
import com.clipsync.android.sync.InboxDelivery
import com.clipsync.android.sync.SyncConnectionState
import com.clipsync.android.sync.SyncTransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * What the preferences page needs to say under its switches beyond the switch position itself:
 * whether the service the master switch controls is actually alive and connected, which read
 * route really listens (or which gate holds it closed), what the capture policy has counted
 * this run, and whether the system lets the app post notifications at all. Every field is a
 * live fact from the service, the coordinator or the session — nothing here is inferred from
 * the preference values.
 */
data class PreferencesRuntimeFacts(
    val serviceRunning: Boolean,
    val connected: Boolean,
    val serviceStartErrorCode: String?,
    val activeReadMode: ClipboardReadMode?,
    val captureRunning: Boolean,
    val captureGate: CaptureGate,
    val tally: CaptureTallySnapshot,
    /** Null = not probed on this build; false = the system surface is switched off. */
    val systemNotificationsEnabled: Boolean?,
    /**
     * The wire version of the live IP session (2 = image frames negotiated, 1 = text only);
     * null while not connected over IP or when the dialer did not report it.
     */
    val ipSessionProtocolVersion: Int? = null,
    /** Outcome of the most recent remote clip of each kind this process tried to write to the clipboard. */
    val lastInboxApply: InboxApplyOutcomes = InboxApplyOutcomes(),
    /**
     * Whether the Bluetooth fallback may open connections right now (BLUETOOTH_CONNECT on
     * API 31+; always true below). Null = not probed on this build.
     */
    val bluetoothPermissionGranted: Boolean? = null,
)

/**
 * Combines the service's live flows with the capture stack's coordinator, session and tally
 * into one fact stream. [notificationsEnabled] and [bluetoothPermissionGranted] are sampled on
 * every emission (system permissions have no flow of their own), so [refreshTicks] lets the
 * host re-sample them when the Activity comes back to the foreground; the service and
 * coordinator flows default to the process-wide ones.
 */
@Suppress("LongParameterList")
fun preferencesRuntimeFacts(
    stack: CaptureStack,
    serviceRunning: Flow<Boolean> = ClipboardSyncService.serviceRunning,
    connectionStates: Flow<SyncConnectionState> = ClipboardSyncService.connectionStates,
    startErrorCodes: Flow<String?> = ClipboardSyncService.startErrorCodes,
    inboxApplyOutcomes: Flow<InboxApplyOutcomes> = InboxDelivery.lastApplyOutcomes,
    refreshTicks: Flow<Unit> = flowOf(Unit),
    bluetoothPermissionGranted: (() -> Boolean)? = null,
    notificationsEnabled: (() -> Boolean)? = null,
): Flow<PreferencesRuntimeFacts> {
    val service =
        combine(serviceRunning, connectionStates, startErrorCodes) { running, connection, startError ->
            ServiceFacts(running, connection, startError)
        }
    val capture =
        combine(stack.coordinator.states, stack.session.status, stack.tally.snapshots) { access, session, tally ->
            Triple(access, session, tally)
        }
    return combine(service, capture, inboxApplyOutcomes, refreshTicks) { svc, (access, session, tally), inboxApply, _ ->
        runtimeFacts(
            service = svc,
            access = access,
            session = session,
            tally = tally,
            inboxApply = inboxApply,
            bluetoothPermissionGranted = bluetoothPermissionGranted?.invoke(),
            notificationsEnabled = notificationsEnabled?.invoke(),
        )
    }
}

private data class ServiceFacts(
    val running: Boolean,
    val connection: SyncConnectionState,
    val startError: String?,
)

@Suppress("LongParameterList")
private fun runtimeFacts(
    service: ServiceFacts,
    access: ClipboardAccessState,
    session: CaptureSessionStatus,
    tally: CaptureTallySnapshot,
    inboxApply: InboxApplyOutcomes,
    bluetoothPermissionGranted: Boolean?,
    notificationsEnabled: Boolean?,
): PreferencesRuntimeFacts {
    val connection = service.connection
    return PreferencesRuntimeFacts(
        serviceRunning = service.running,
        connected = connection is SyncConnectionState.Connected,
        serviceStartErrorCode = service.startError,
        activeReadMode = access.activeReadMode,
        captureRunning = session.running,
        captureGate = session.gate,
        tally = tally,
        systemNotificationsEnabled = notificationsEnabled,
        ipSessionProtocolVersion =
            (connection as? SyncConnectionState.Connected)
                ?.takeIf { it.transport == SyncTransportKind.IP }
                ?.protocolVersion,
        lastInboxApply = inboxApply,
        bluetoothPermissionGranted = bluetoothPermissionGranted,
    )
}
