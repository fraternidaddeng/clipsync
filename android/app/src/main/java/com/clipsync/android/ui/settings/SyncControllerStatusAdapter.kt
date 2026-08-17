package com.clipsync.android.ui.settings

import com.clipsync.android.service.ServiceProcessState
import com.clipsync.android.service.ServiceSnapshot
import com.clipsync.android.sync.SyncController
import com.clipsync.android.sync.SyncStatus

/**
 * Maps [SyncController] onto the settings/health cards. Process status comes
 * from the Stage 5 orchestrator; a killed service never reports fake-online.
 */
class SyncControllerStatusAdapter(
    private val controller: () -> SyncController?,
    private val isPaired: () -> Boolean,
    private val serviceSnapshot: () -> ServiceSnapshot = { ServiceSnapshot.idle() },
) : SyncStatusProvider {
    override fun current(): SyncConnectionStatus {
        val snap = controller()?.status()
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
}
