package com.clipsync.android.ui.wizard

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardWriteMode
import com.clipsync.android.platform.clipboard.SelfTestResult

enum class WizardStepId {
    NOTIFICATIONS,
    FOREGROUND_SERVICE,
    IGNORE_BATTERY,
    OVERLAY,
    READ_LOGS,
    SHIZUKU_BINDER,
    SHIZUKU_AUTH,
}

enum class WizardActionKind {
    REQUEST_RUNTIME_PERMISSION,
    OPEN_SYSTEM_SETTINGS,
    RECHECK_ADB,
    OPEN_SHIZUKU,
}

data class WizardChoices(
    val preferredReadMode: ClipboardReadMode = ClipboardReadMode.SHIZUKU_EVENT,
    val autoFallbackAllowed: Boolean = true,
    val pollingIntervalMs: Int = DEFAULT_POLLING_INTERVAL_MS,
    val backgroundAutoUpload: Boolean = false,
    val backgroundAutoApply: Boolean = true,
    val overlayConsented: Boolean = false,
    val writeMode: ClipboardWriteMode = ClipboardWriteMode.PUBLIC_API,
    val wizardCompleted: Boolean = false,
) {
    companion object {
        const val MIN_POLLING_INTERVAL_MS = 500
        const val MAX_POLLING_INTERVAL_MS = 2000
        const val DEFAULT_POLLING_INTERVAL_MS = 800

        fun clampPollingIntervalMs(ms: Int): Int =
            ms.coerceIn(MIN_POLLING_INTERVAL_MS, MAX_POLLING_INTERVAL_MS)
    }
}

data class ReadLogsGuidance(
    val adbOnly: Boolean = true,
    val bootstrapScript: String = BOOTSTRAP_SCRIPT,
    val inAppDialogAllowed: Boolean = false,
    val recheckAfterInstallUpgradeReboot: Boolean = true,
) {
    companion object {
        const val BOOTSTRAP_SCRIPT = "android-bootstrap.ps1"
        val Default = ReadLogsGuidance()
    }
}

data class WizardStepStatus(
    val id: WizardStepId,
    val state: CapabilityState,
    val skipped: Boolean,
    val completed: Boolean,
    val actionKind: WizardActionKind,
    val offersInAppGrant: Boolean,
    val readLogsGuidance: ReadLogsGuidance? = null,
)

data class LiveIndicators(
    val network: CapabilityState,
    val service: CapabilityState,
    val backgroundRead: CapabilityState,
    val backgroundWrite: CapabilityState,
) {
    fun allReady(): Boolean =
        network == CapabilityState.READY &&
            service == CapabilityState.READY &&
            backgroundRead == CapabilityState.READY &&
            backgroundWrite == CapabilityState.READY
}

data class WizardSkipEffects(
    val unavailableReadModes: Set<ClipboardReadMode> = emptySet(),
    val notificationActionsHidden: Boolean = false,
    val foregroundServiceLimited: Boolean = false,
    val batteryMayKillProcess: Boolean = false,
    val manualFallbackAvailable: Boolean = true,
)

/** Plan 5.7 self-test card state. Results carry states and codes, never text. */
data class SelfTestUiState(
    val running: Boolean = false,
    val read: SelfTestResult? = null,
    val write: SelfTestResult? = null,
)

data class WizardUiState(
    val steps: List<WizardStepStatus>,
    val choices: WizardChoices,
    val indicators: LiveIndicators,
    val skipEffects: WizardSkipEffects,
    val overlayEnabled: Boolean,
    val canFinish: Boolean,
    val manualFallbackAvailable: Boolean = true,
)

data class WizardProbes(
    val notifications: () -> CapabilityState,
    val foregroundService: () -> CapabilityState,
    val ignoreBattery: () -> CapabilityState,
    val overlay: () -> CapabilityState,
    val readLogs: () -> CapabilityState,
    val shizukuBinder: () -> CapabilityState,
    val shizukuAuth: () -> CapabilityState,
    val network: () -> CapabilityState,
    val service: () -> CapabilityState,
    val backgroundRead: () -> CapabilityState,
    val backgroundWrite: () -> CapabilityState,
) {
    fun forStep(id: WizardStepId): () -> CapabilityState = when (id) {
        WizardStepId.NOTIFICATIONS -> notifications
        WizardStepId.FOREGROUND_SERVICE -> foregroundService
        WizardStepId.IGNORE_BATTERY -> ignoreBattery
        WizardStepId.OVERLAY -> overlay
        WizardStepId.READ_LOGS -> readLogs
        WizardStepId.SHIZUKU_BINDER -> shizukuBinder
        WizardStepId.SHIZUKU_AUTH -> shizukuAuth
    }

    companion object {
        fun unknown(): WizardProbes {
            val unknown = { CapabilityState.UNKNOWN }
            return WizardProbes(
                notifications = unknown,
                foregroundService = unknown,
                ignoreBattery = unknown,
                overlay = unknown,
                readLogs = unknown,
                shizukuBinder = unknown,
                shizukuAuth = unknown,
                network = unknown,
                service = unknown,
                backgroundRead = unknown,
                backgroundWrite = unknown,
            )
        }
    }
}

object WizardNavigation {
    const val TAB_INDEX = 4
}

internal fun actionKindFor(id: WizardStepId): WizardActionKind = when (id) {
    WizardStepId.NOTIFICATIONS -> WizardActionKind.REQUEST_RUNTIME_PERMISSION
    WizardStepId.FOREGROUND_SERVICE,
    WizardStepId.IGNORE_BATTERY,
    WizardStepId.OVERLAY,
    -> WizardActionKind.OPEN_SYSTEM_SETTINGS
    WizardStepId.READ_LOGS -> WizardActionKind.RECHECK_ADB
    WizardStepId.SHIZUKU_BINDER,
    WizardStepId.SHIZUKU_AUTH,
    -> WizardActionKind.OPEN_SHIZUKU
}

internal fun offersInAppGrant(id: WizardStepId): Boolean =
    id == WizardStepId.NOTIFICATIONS

internal fun skipEffectsOf(skipped: Set<WizardStepId>): WizardSkipEffects {
    val modes = linkedSetOf<ClipboardReadMode>()
    if (WizardStepId.OVERLAY in skipped) {
        modes += ClipboardReadMode.OVERLAY_POLLING
        modes += ClipboardReadMode.ADB_LOG_OVERLAY
    }
    if (WizardStepId.READ_LOGS in skipped) {
        modes += ClipboardReadMode.ADB_LOG_OVERLAY
    }
    if (WizardStepId.SHIZUKU_BINDER in skipped || WizardStepId.SHIZUKU_AUTH in skipped) {
        modes += ClipboardReadMode.SHIZUKU_EVENT
    }
    return WizardSkipEffects(
        unavailableReadModes = modes,
        notificationActionsHidden = WizardStepId.NOTIFICATIONS in skipped,
        foregroundServiceLimited = WizardStepId.FOREGROUND_SERVICE in skipped,
        batteryMayKillProcess = WizardStepId.IGNORE_BATTERY in skipped,
        manualFallbackAvailable = true,
    )
}
