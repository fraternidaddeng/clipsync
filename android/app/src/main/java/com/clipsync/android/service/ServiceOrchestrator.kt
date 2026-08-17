package com.clipsync.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM-testable owner of ForegroundService start/stop, Activity↔Service controller
 * handover, and honest process status. The Android [ClipboardSyncService] shell
 * stays thin and calls into this class.
 *
 * Foreground service alive ≠ clipboard permission (plan 0.1.2 rule 6).
 */
class ServiceOrchestrator {
    private val mutableSnapshots = MutableStateFlow(ServiceSnapshot())
    val snapshots: StateFlow<ServiceSnapshot> = mutableSnapshots.asStateFlow()

    var processState: ServiceProcessState = ServiceProcessState.STOPPED
        private set
    var controllerOwner: ControllerOwner = ControllerOwner.ACTIVITY
        private set
    var errorCode: String? = null
        private set
    var wantedRunning: Boolean = false
        set(value) {
            field = value
            publish()
        }
    var notificationsVisible: Boolean = true
        set(value) {
            field = value
            publish()
        }
    var bootRecoveryEnabled: Boolean = false
        private set
    var controllerReady: Boolean = false
        private set

    val isProcessAlive: Boolean get() = snapshot().isProcessAlive
    val isOnline: Boolean get() = snapshot().isOnline

    fun snapshot(): ServiceSnapshot = ServiceSnapshot(
        processState = processState,
        controllerOwner = controllerOwner,
        errorCode = errorCode,
        wantedRunning = wantedRunning,
        notificationsVisible = notificationsVisible,
        controllerReady = controllerReady,
    )

    fun statusLabel(): String = snapshot().statusLabel()

    fun requestBackgroundStart(): ControllerHandover {
        wantedRunning = true
        controllerReady = false
        errorCode = null
        processState = ServiceProcessState.STARTING
        controllerOwner = ControllerOwner.NONE
        publish()
        return ControllerHandover(
            releaseFrom = ControllerOwner.ACTIVITY,
            acquireBy = ControllerOwner.SERVICE,
        )
    }

    fun requestBackgroundStop(): ControllerHandover {
        wantedRunning = false
        controllerReady = false
        errorCode = null
        processState = ServiceProcessState.STOPPED
        controllerOwner = ControllerOwner.ACTIVITY
        publish()
        return ControllerHandover(
            releaseFrom = ControllerOwner.SERVICE,
            acquireBy = ControllerOwner.ACTIVITY,
        )
    }

    fun onForegroundStarted() {
        if (processState == ServiceProcessState.ERROR) {
            return
        }
        errorCode = null
        processState = ServiceProcessState.STARTING
        publish()
    }

    fun onServiceControllerStarted() {
        if (processState == ServiceProcessState.ERROR) {
            return
        }
        controllerOwner = ControllerOwner.SERVICE
        processState = ServiceProcessState.RUNNING
        publish()
    }

    fun markControllerReady() {
        if (processState == ServiceProcessState.RUNNING &&
            controllerOwner == ControllerOwner.SERVICE
        ) {
            controllerReady = true
            publish()
        }
    }

    fun clearControllerReady() {
        controllerReady = false
        publish()
    }

    fun onForegroundStartFailed(throwable: Throwable) {
        errorCode = ForegroundStartErrors.map(throwable)
        processState = ServiceProcessState.ERROR
        controllerOwner = ControllerOwner.NONE
        controllerReady = false
        publish()
    }

    fun onProcessKilled() {
        controllerOwner = ControllerOwner.NONE
        controllerReady = false
        processState = ServiceProcessState.NEEDS_RECOVERY
        publish()
    }

    fun onStickyRestart() {
        controllerReady = false
        controllerOwner = ControllerOwner.NONE
        processState = ServiceProcessState.NEEDS_RECOVERY
        publish()
    }

    fun onNetworkRegained(): Boolean =
        processState == ServiceProcessState.RUNNING &&
            controllerOwner == ControllerOwner.SERVICE

    fun setBootRecoveryEnabled(enabled: Boolean) {
        bootRecoveryEnabled = enabled
        publish()
    }

    private fun publish() {
        mutableSnapshots.value = snapshot()
    }

    fun bootReceiverShouldBeEnabled(): Boolean = bootRecoveryEnabled

    fun onBootCompleted(startFgs: () -> Boolean): BootOutcome {
        if (!bootRecoveryEnabled || !wantedRunning) {
            return BootOutcome.Ignored
        }
        return try {
            if (startFgs()) {
                BootOutcome.Started
            } else {
                BootOutcome.RequestUserRecovery
            }
        } catch (_: Exception) {
            BootOutcome.RequestUserRecovery
        }
    }

    fun applyRelease(
        handover: ControllerHandover,
        activity: SyncControllerLease,
        service: SyncControllerLease,
    ) {
        when (handover.releaseFrom) {
            ControllerOwner.ACTIVITY -> activity.stop()
            ControllerOwner.SERVICE -> service.stop()
            ControllerOwner.NONE -> Unit
        }
    }

    fun applyAcquire(
        handover: ControllerHandover,
        activity: SyncControllerLease,
        service: SyncControllerLease,
    ) {
        when (handover.acquireBy) {
            ControllerOwner.ACTIVITY -> activity.start()
            ControllerOwner.SERVICE -> service.start()
            ControllerOwner.NONE -> Unit
        }
    }

    fun buildNotificationSpec(): ServiceNotificationSpec {
        val text = if (notificationsVisible) {
            ServiceNotificationActions.TEXT
        } else {
            ServiceNotificationActions.TEXT_HIDDEN
        }
        return ServiceNotificationSpec(
            channelId = ServiceNotificationActions.CHANNEL_ID,
            title = ServiceNotificationActions.TITLE,
            text = text,
            ongoing = true,
            extras = emptyMap(),
            actions = listOf(
                ServiceNotificationAction(
                    id = ServiceNotificationActions.PAUSE_ALL,
                    intentAction = ServiceNotificationActions.ACTION_PAUSE_ALL,
                    componentClass = ServiceNotificationActions.COMPONENT_SERVICE,
                    title = ServiceNotificationActions.ACTION_TITLE_PAUSE,
                ),
                ServiceNotificationAction(
                    id = ServiceNotificationActions.SYNC_NOW,
                    intentAction = ServiceNotificationActions.ACTION_SYNC_NOW,
                    componentClass = ServiceNotificationActions.COMPONENT_SERVICE,
                    title = ServiceNotificationActions.ACTION_TITLE_SYNC_NOW,
                ),
                ServiceNotificationAction(
                    id = ServiceNotificationActions.OPEN_STATUS,
                    intentAction = ServiceNotificationActions.ACTION_OPEN_STATUS,
                    componentClass = ServiceNotificationActions.COMPONENT_ACTIVITY,
                    title = ServiceNotificationActions.ACTION_TITLE_OPEN_STATUS,
                    extras = mapOf(
                        ServiceNotificationActions.EXTRA_OPEN_TAB to ServiceNotificationActions.TAB_STATUS,
                    ),
                ),
            ),
        )
    }
}
