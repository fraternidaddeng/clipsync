package com.clipsync.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM-testable owner of ForegroundService start/stop and honest process status.
 * The Android [ClipboardSyncService] shell stays thin and calls into this class.
 * The sync controller itself is a single process-scoped instance owned by
 * [ClipboardSyncRuntime]; this class only tracks the FGS process lifecycle.
 *
 * Foreground service alive ≠ clipboard permission (plan 0.1.2 rule 6).
 */
class ServiceOrchestrator {
    private val mutableSnapshots = MutableStateFlow(ServiceSnapshot())
    val snapshots: StateFlow<ServiceSnapshot> = mutableSnapshots.asStateFlow()

    var processState: ServiceProcessState = ServiceProcessState.STOPPED
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
        errorCode = errorCode,
        wantedRunning = wantedRunning,
        notificationsVisible = notificationsVisible,
        controllerReady = controllerReady,
    )

    fun statusLabel(): String = snapshot().statusLabel()

    fun requestBackgroundStart() {
        wantedRunning = true
        controllerReady = false
        errorCode = null
        processState = ServiceProcessState.STARTING
        publish()
    }

    fun requestBackgroundStop() {
        wantedRunning = false
        controllerReady = false
        errorCode = null
        processState = ServiceProcessState.STOPPED
        publish()
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
        processState = ServiceProcessState.RUNNING
        publish()
    }

    fun markControllerReady() {
        if (processState == ServiceProcessState.RUNNING) {
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
        controllerReady = false
        publish()
    }

    fun onProcessKilled() {
        controllerReady = false
        processState = ServiceProcessState.NEEDS_RECOVERY
        publish()
    }

    fun onStickyRestart() {
        controllerReady = false
        processState = ServiceProcessState.NEEDS_RECOVERY
        publish()
    }

    /**
     * Bounded boot health-check exhausted and the FGS is still not RUNNING.
     * Does not demote a service that came up between the decision and this call.
     */
    fun onBootHealthCheckFailed() {
        if (processState == ServiceProcessState.RUNNING) {
            return
        }
        controllerReady = false
        processState = ServiceProcessState.NEEDS_RECOVERY
        publish()
    }

    fun onNetworkRegained(): Boolean = processState == ServiceProcessState.RUNNING

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
