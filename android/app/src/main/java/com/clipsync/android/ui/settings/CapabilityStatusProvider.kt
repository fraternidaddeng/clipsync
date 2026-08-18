package com.clipsync.android.ui.settings

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.ui.HealthStatus
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue

fun interface CapabilityStatusProvider {
    fun snapshot(): CapabilitySnapshot
}

data class CapabilitySnapshot(
    val read: HealthValue,
    val write: HealthValue,
)

class FixedCapabilityStatus(
    private val read: HealthValue,
    private val write: HealthValue,
) : CapabilityStatusProvider {
    override fun snapshot(): CapabilitySnapshot = CapabilitySnapshot(read, write)
}

class LiveCapabilityStatus(
    private val read: () -> HealthValue,
    private val write: () -> HealthValue,
) : CapabilityStatusProvider {
    override fun snapshot(): CapabilitySnapshot = CapabilitySnapshot(read(), write())
}

fun networkCard(status: SyncConnectionStatus): HealthValue =
    when {
        !status.paired -> HealthValue(HealthStatus.UNPAIRED, HealthTone.NEUTRAL)
        status.windowsReachable -> HealthValue(HealthStatus.CONNECTED, HealthTone.GOOD)
        else -> HealthValue(HealthStatus.WINDOWS_UNREACHABLE, HealthTone.WARNING)
    }

fun serviceCard(status: SyncConnectionStatus): HealthValue =
    when {
        status.serviceErrorCode != null ->
            HealthValue(
                serviceErrorLabel(status.serviceErrorCode),
                HealthTone.WARNING,
            )
        status.serviceNeedsRecovery -> HealthValue(HealthStatus.NEEDS_RECOVERY, HealthTone.WARNING)
        status.serviceRunning && status.notificationsHidden ->
            HealthValue(HealthStatus.RUNNING_HIDDEN, HealthTone.GOOD)
        status.serviceRunning -> HealthValue(HealthStatus.RUNNING, HealthTone.GOOD)
        else -> HealthValue(HealthStatus.NOT_RUNNING, HealthTone.NEUTRAL)
    }

fun serviceErrorLabel(code: String): HealthStatus =
    when (code) {
        "missing_fgs_type" -> HealthStatus.FGS_TYPE_MISSING
        "fgs_security" -> HealthStatus.FGS_PERMISSION_MISSING
        else -> HealthStatus.SERVICE_START_DENIED
    }

fun healthRead(state: CapabilityState?): HealthValue =
    when (state) {
        CapabilityState.READY -> HealthValue(HealthStatus.FOREGROUND_READY, HealthTone.GOOD)
        CapabilityState.DEGRADED -> HealthValue(HealthStatus.DEGRADED, HealthTone.WARNING)
        CapabilityState.UNAVAILABLE -> HealthValue(HealthStatus.UNAVAILABLE, HealthTone.WARNING)
        CapabilityState.NEEDS_USER_ACTION -> HealthValue(HealthStatus.NEEDS_ACTION, HealthTone.WARNING)
        CapabilityState.UNKNOWN, null -> HealthValue(HealthStatus.FOREGROUND_ONLY, HealthTone.NEUTRAL)
    }

/**
 * Read card for a live capture stack: READY names the ACTIVE backend instead
 * of pretending everything is the foreground probe (device finding: the status
 * tab claimed "Foreground ready" while capture ran on Shizuku).
 */
fun healthReadForActiveMode(
    mode: ClipboardReadMode,
    state: CapabilityState,
): HealthValue =
    if (state == CapabilityState.READY) {
        when (mode) {
            ClipboardReadMode.SHIZUKU_EVENT ->
                HealthValue(HealthStatus.READ_READY_SHIZUKU, HealthTone.GOOD)
            ClipboardReadMode.ADB_LOG_OVERLAY ->
                HealthValue(HealthStatus.READ_READY_ADB, HealthTone.GOOD)
            ClipboardReadMode.OVERLAY_POLLING ->
                HealthValue(HealthStatus.READ_READY_OVERLAY, HealthTone.GOOD)
            ClipboardReadMode.FOREGROUND_ONLY ->
                HealthValue(HealthStatus.FOREGROUND_READY, HealthTone.GOOD)
        }
    } else {
        healthRead(state)
    }

fun healthWrite(state: CapabilityState): HealthValue =
    when (state) {
        CapabilityState.READY -> HealthValue(HealthStatus.PUBLIC_WRITE_READY, HealthTone.GOOD)
        CapabilityState.DEGRADED -> HealthValue(HealthStatus.DEGRADED, HealthTone.WARNING)
        CapabilityState.UNAVAILABLE -> HealthValue(HealthStatus.UNAVAILABLE, HealthTone.WARNING)
        CapabilityState.NEEDS_USER_ACTION -> HealthValue(HealthStatus.NEEDS_ACTION, HealthTone.WARNING)
        CapabilityState.UNKNOWN -> HealthValue(HealthStatus.NOT_PROBED, HealthTone.NEUTRAL)
    }
