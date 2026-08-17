package com.clipsync.android.ui.settings

import com.clipsync.android.service.ServiceProcessState
import com.clipsync.android.service.ServiceSnapshot
import com.clipsync.android.sync.SyncController
import com.clipsync.android.sync.SyncControllerState
import com.clipsync.android.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Maps [SyncController] onto the settings/health cards. Process status comes
 * from the Stage 5 orchestrator; a killed service never reports fake-online.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerStatusAdapter(
    private val controller: () -> SyncController? = { null },
    private val isPaired: () -> Boolean,
    private val serviceSnapshot: () -> ServiceSnapshot = { ServiceSnapshot.idle() },
    private val serviceSnapshots: Flow<ServiceSnapshot> = flow { emit(serviceSnapshot()) },
    private val controllerTicks: Flow<Int> = flowOf(0),
    private val controllerState: () -> StateFlow<SyncControllerState>? = { controller()?.state },
) : SyncStatusProvider {
    override fun current(): SyncConnectionStatus {
        val snap = controllerState()?.value ?: controller()?.status()
        val paired = isPaired()
        val service = serviceSnapshot()
        val blocked = service.processState == ServiceProcessState.NEEDS_RECOVERY ||
            service.processState == ServiceProcessState.ERROR
        return SyncConnectionStatus(
            paired = paired,
            windowsReachable = paired && snap?.status == SyncStatus.READY && !blocked,
            serviceRunning = service.isProcessAlive,
            serviceNeedsRecovery = service.processState == ServiceProcessState.NEEDS_RECOVERY,
            serviceErrorCode = service.errorCode,
            notificationsHidden = !service.notificationsVisible,
        )
    }

    override fun snapshots(): Flow<SyncConnectionStatus> =
        combine(serviceSnapshots, controllerTicks) { _, _ -> }
            .flatMapLatest {
                val states = controllerState()
                if (states == null) {
                    flowOf(current())
                } else {
                    states.map { current() }
                }
            }
}
