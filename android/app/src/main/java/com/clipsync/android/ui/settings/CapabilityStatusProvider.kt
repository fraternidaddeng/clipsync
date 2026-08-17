package com.clipsync.android.ui.settings

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

fun networkCard(status: SyncConnectionStatus): HealthValue = when {
    !status.paired -> HealthValue("Unpaired", HealthTone.NEUTRAL)
    status.windowsReachable -> HealthValue("Connected", HealthTone.GOOD)
    else -> HealthValue("Windows unreachable", HealthTone.WARNING)
}

fun serviceCard(status: SyncConnectionStatus): HealthValue =
    if (status.serviceRunning) {
        HealthValue("Running", HealthTone.GOOD)
    } else {
        HealthValue("Not running", HealthTone.NEUTRAL)
    }
