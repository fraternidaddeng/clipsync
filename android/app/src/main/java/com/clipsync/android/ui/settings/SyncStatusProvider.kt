package com.clipsync.android.ui.settings

/**
 * Tiny status surface for settings/health cards. Agent D's WebSocket dialer does not
 * exist in this tree yet; [NoOpSyncStatusProvider] / [FixedSyncStatusProvider] stand in
 * until a real [SyncController] can implement this. Stage 5 owns ForegroundService.
 */
data class SyncConnectionStatus(
    val paired: Boolean,
    val windowsReachable: Boolean,
    val serviceRunning: Boolean,
    val serviceNeedsRecovery: Boolean = false,
    val serviceErrorCode: String? = null,
    val notificationsHidden: Boolean = false,
)

fun interface SyncStatusProvider {
    fun current(): SyncConnectionStatus
}

class NoOpSyncStatusProvider : SyncStatusProvider {
    override fun current(): SyncConnectionStatus =
        SyncConnectionStatus(paired = false, windowsReachable = false, serviceRunning = false)
}

class FixedSyncStatusProvider(
    private val status: SyncConnectionStatus,
) : SyncStatusProvider {
    override fun current(): SyncConnectionStatus = status
}

class PairingAwareSyncStatusProvider(
    private val isPaired: () -> Boolean,
    private val windowsReachable: () -> Boolean = { false },
    private val serviceRunning: () -> Boolean = { false },
) : SyncStatusProvider {
    override fun current(): SyncConnectionStatus =
        SyncConnectionStatus(
            paired = isPaired(),
            windowsReachable = windowsReachable(),
            serviceRunning = serviceRunning(),
        )
}
