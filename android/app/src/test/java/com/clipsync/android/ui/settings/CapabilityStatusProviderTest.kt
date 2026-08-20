package com.clipsync.android.ui.settings

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.ui.HealthStatus
import com.clipsync.android.ui.HealthTone
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityStatusProviderTest {
    @Test
    fun `ready read card names the active backend`() {
        assertEquals(
            HealthStatus.READ_READY_SHIZUKU,
            healthReadForActiveMode(ClipboardReadMode.SHIZUKU_EVENT, CapabilityState.READY).label,
        )
        assertEquals(
            HealthStatus.READ_READY_ADB,
            healthReadForActiveMode(ClipboardReadMode.ADB_LOG_OVERLAY, CapabilityState.READY).label,
        )
        assertEquals(
            HealthStatus.READ_READY_OVERLAY,
            healthReadForActiveMode(ClipboardReadMode.OVERLAY_POLLING, CapabilityState.READY).label,
        )
        assertEquals(
            HealthStatus.FOREGROUND_READY,
            healthReadForActiveMode(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.READY).label,
        )
        assertEquals(
            HealthTone.GOOD,
            healthReadForActiveMode(ClipboardReadMode.SHIZUKU_EVENT, CapabilityState.READY).tone,
        )
    }

    @Test
    fun `parked last read state uses the shared state mapping`() {
        assertEquals(
            HealthStatus.UNAVAILABLE,
            healthRead(CapabilityState.UNAVAILABLE).label,
        )
        assertEquals(
            HealthStatus.NEEDS_ACTION,
            healthRead(CapabilityState.NEEDS_USER_ACTION).label,
        )
        assertEquals(
            HealthStatus.FOREGROUND_ONLY,
            healthRead(null).label,
        )
    }

    @Test
    fun `non-ready read card falls back to the shared state mapping`() {
        assertEquals(
            HealthStatus.DEGRADED,
            healthReadForActiveMode(
                ClipboardReadMode.OVERLAY_POLLING,
                CapabilityState.DEGRADED,
            ).label,
        )
        assertEquals(
            HealthStatus.NEEDS_ACTION,
            healthReadForActiveMode(
                ClipboardReadMode.SHIZUKU_EVENT,
                CapabilityState.NEEDS_USER_ACTION,
            ).label,
        )
    }
}
