package com.clipsync.android.ui.settings

import com.clipsync.android.sync.SyncController
import com.clipsync.android.sync.SyncStatus

/**
 * Maps Agent D's [SyncController] onto the settings/health cards.
 * Service stays "not running" here: ForegroundService is Stage 5.
 */
class SyncControllerStatusAdapter(
    private val controller: () -> SyncController?,
    private val isPaired: () -> Boolean,
) : SyncStatusProvider {
    override fun current(): SyncConnectionStatus {
        val snap = controller()?.status()
        val paired = isPaired()
        return SyncConnectionStatus(
            paired = paired,
            windowsReachable = paired && snap?.status == SyncStatus.READY,
            serviceRunning = false,
        )
    }
}
