package com.clipsync.android.ui.prefs

import com.clipsync.android.platform.clipboard.CaptureGate
import com.clipsync.android.platform.clipboard.CaptureSessionStatus
import com.clipsync.android.platform.clipboard.ClipboardAccessState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.sync.CaptureStack
import com.clipsync.android.sync.CaptureTallySnapshot
import com.clipsync.android.sync.ClipboardSyncService
import com.clipsync.android.sync.SyncConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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
)

/**
 * Combines the service's live flows with the capture stack's coordinator, session and tally
 * into one fact stream. [notificationsEnabled] is sampled on every emission (the system surface
 * has no flow of its own); the service and coordinator flows default to the process-wide ones.
 */
fun preferencesRuntimeFacts(
    stack: CaptureStack,
    serviceRunning: Flow<Boolean> = ClipboardSyncService.serviceRunning,
    connectionStates: Flow<SyncConnectionState> = ClipboardSyncService.connectionStates,
    startErrorCodes: Flow<String?> = ClipboardSyncService.startErrorCodes,
    notificationsEnabled: (() -> Boolean)? = null,
): Flow<PreferencesRuntimeFacts> {
    val service =
        combine(serviceRunning, connectionStates, startErrorCodes) { running, connection, startError ->
            Triple(running, connection is SyncConnectionState.Connected, startError)
        }
    val capture =
        combine(stack.coordinator.states, stack.session.status, stack.tally.snapshots) { access, session, tally ->
            Triple(access, session, tally)
        }
    return combine(service, capture) { (running, connected, startError), (access, session, tally) ->
        runtimeFacts(
            serviceRunning = running,
            connected = connected,
            startError = startError,
            access = access,
            session = session,
            tally = tally,
            notificationsEnabled = notificationsEnabled?.invoke(),
        )
    }
}

@Suppress("LongParameterList")
private fun runtimeFacts(
    serviceRunning: Boolean,
    connected: Boolean,
    startError: String?,
    access: ClipboardAccessState,
    session: CaptureSessionStatus,
    tally: CaptureTallySnapshot,
    notificationsEnabled: Boolean?,
): PreferencesRuntimeFacts =
    PreferencesRuntimeFacts(
        serviceRunning = serviceRunning,
        connected = connected,
        serviceStartErrorCode = startError,
        activeReadMode = access.activeReadMode,
        captureRunning = session.running,
        captureGate = session.gate,
        tally = tally,
        systemNotificationsEnabled = notificationsEnabled,
    )
