package com.clipsync.android.ui.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

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

    fun snapshots(): Flow<SyncConnectionStatus> = flow { emit(current()) }
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

/** Test/live seam: History and Settings collect [snapshots] so READY is not a one-shot pull. */
class MutableSyncStatusProvider(
    initial: SyncConnectionStatus,
) : SyncStatusProvider {
    private val mutableStatus = MutableStateFlow(initial)

    override fun current(): SyncConnectionStatus = mutableStatus.value

    override fun snapshots(): Flow<SyncConnectionStatus> = mutableStatus.asStateFlow()

    fun set(status: SyncConnectionStatus) {
        mutableStatus.value = status
    }
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
