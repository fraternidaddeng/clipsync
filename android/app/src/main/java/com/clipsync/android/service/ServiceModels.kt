package com.clipsync.android.service

enum class ServiceProcessState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
    NEEDS_RECOVERY,
}

enum class ControllerOwner {
    NONE,
    ACTIVITY,
    SERVICE,
}

data class ControllerHandover(
    val releaseFrom: ControllerOwner,
    val acquireBy: ControllerOwner,
)

enum class BootOutcome {
    Ignored,
    Started,
    RequestUserRecovery,
}

interface SyncControllerLease {
    val started: Boolean
    fun start()
    fun stop()
}

data class ServiceSnapshot(
    val processState: ServiceProcessState = ServiceProcessState.STOPPED,
    val controllerOwner: ControllerOwner = ControllerOwner.NONE,
    val errorCode: String? = null,
    val wantedRunning: Boolean = false,
    val notificationsVisible: Boolean = true,
    val controllerReady: Boolean = false,
) {
    val isProcessAlive: Boolean get() = processState == ServiceProcessState.RUNNING
    val isOnline: Boolean
        get() = processState == ServiceProcessState.RUNNING &&
            controllerOwner == ControllerOwner.SERVICE &&
            controllerReady

    fun statusLabel(): String = when (processState) {
        ServiceProcessState.RUNNING ->
            if (notificationsVisible) LABEL_RUNNING else LABEL_RUNNING_HIDDEN
        ServiceProcessState.STARTING -> LABEL_STARTING
        ServiceProcessState.ERROR -> errorLabel(errorCode)
        ServiceProcessState.NEEDS_RECOVERY -> LABEL_NEEDS_RECOVERY
        ServiceProcessState.STOPPED -> LABEL_NOT_RUNNING
    }

    companion object {
        const val LABEL_RUNNING = "Running"
        const val LABEL_RUNNING_HIDDEN = "Running (notification hidden)"
        const val LABEL_STARTING = "Starting"
        const val LABEL_NEEDS_RECOVERY = "Needs recovery"
        const val LABEL_NOT_RUNNING = "Not running"
        const val LABEL_MISSING_TYPE = "Foreground service type missing"
        const val LABEL_SECURITY = "Foreground service permission missing"
        const val LABEL_OEM_DENIED = "Service start denied"

        fun errorLabel(code: String?): String = when (code) {
            ForegroundStartErrors.MISSING_TYPE -> LABEL_MISSING_TYPE
            ForegroundStartErrors.SECURITY -> LABEL_SECURITY
            else -> LABEL_OEM_DENIED
        }

        fun idle(): ServiceSnapshot = ServiceSnapshot()
    }
}

object ForegroundStartErrors {
    const val MISSING_TYPE = "missing_fgs_type"
    const val SECURITY = "fgs_security"
    const val OEM_DENIED = "fgs_denied"

    fun map(throwable: Throwable): String {
        val name = throwable.javaClass.simpleName
        return when {
            name == "MissingForegroundServiceTypeException" -> MISSING_TYPE
            throwable is SecurityException -> SECURITY
            else -> OEM_DENIED
        }
    }
}

data class ServiceNotificationAction(
    val id: String,
    val intentAction: String,
    val componentClass: String,
    val title: String,
    val extras: Map<String, String> = emptyMap(),
)

data class ServiceNotificationSpec(
    val channelId: String,
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val actions: List<ServiceNotificationAction>,
    val extras: Map<String, String> = emptyMap(),
) {
    fun allVisibleText(): List<String> =
        listOf(channelId, title, text) + extras.keys + extras.values +
            actions.flatMap { listOf(it.id, it.intentAction, it.componentClass, it.title) + it.extras.keys + it.extras.values }
}

object ServiceNotificationActions {
    const val CHANNEL_ID = "clipsync_sync_service"
    const val PAUSE_ALL = "pause_all"
    const val SYNC_NOW = "sync_now"
    const val OPEN_STATUS = "open_status"
    const val ACTION_PAUSE_ALL = "com.clipsync.android.service.action.PAUSE_ALL"
    const val ACTION_SYNC_NOW = "com.clipsync.android.service.action.SYNC_NOW"
    const val ACTION_OPEN_STATUS = "com.clipsync.android.service.action.OPEN_STATUS"
    const val ACTION_START = "com.clipsync.android.service.action.START"
    const val ACTION_STOP = "com.clipsync.android.service.action.STOP"
    const val COMPONENT_SERVICE = "ClipboardSyncService"
    const val COMPONENT_ACTIVITY = "MainActivity"
    const val EXTRA_OPEN_TAB = "open_tab"
    const val TAB_STATUS = "status"
    const val TITLE = "ClipSync background sync"
    const val TEXT = "Keeping the Windows connection alive. This is not clipboard permission."
    const val TEXT_HIDDEN = "Sync service is running. Notification actions are hidden without permission."
    const val ACTION_TITLE_PAUSE = "Pause all"
    const val ACTION_TITLE_SYNC_NOW = "Sync now"
    const val ACTION_TITLE_OPEN_STATUS = "Open status"
    const val RECOVERY_TITLE = "ClipSync needs recovery"
    const val RECOVERY_TEXT = "Background sync stopped after a restart. Open the app to resume."
}
