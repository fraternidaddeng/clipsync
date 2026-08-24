package com.clipsync.android.platform.clipboard.shizuku

import com.clipsync.android.platform.clipboard.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuErrorCodesTest {
    @Test
    fun `seven stable error codes are exactly the plan 5_3 set`() {
        assertEquals(
            setOf(
                "SHIZUKU_NOT_INSTALLED",
                "SHIZUKU_NOT_RUNNING",
                "SHIZUKU_NOT_AUTHORIZED",
                "SHIZUKU_BINDER_DEAD",
                "SHIZUKU_USERSERVICE_DEAD",
                "CLIPBOARD_BINDER_DEAD",
                "SHIZUKU_API_MISMATCH",
            ),
            ShizukuErrorCodes.ALL,
        )
        assertEquals(7, ShizukuErrorCodes.ALL.size)
    }

    @Test
    fun `probe maps not installed running authorized to needs user action`() {
        assertEquals(
            CapabilityState.NEEDS_USER_ACTION,
            ShizukuErrorCodes.probeReadState(ShizukuErrorCodes.NOT_INSTALLED),
        )
        assertEquals(
            CapabilityState.NEEDS_USER_ACTION,
            ShizukuErrorCodes.probeReadState(ShizukuErrorCodes.NOT_RUNNING),
        )
        assertEquals(
            CapabilityState.NEEDS_USER_ACTION,
            ShizukuErrorCodes.probeReadState(ShizukuErrorCodes.NOT_AUTHORIZED),
        )
    }

    @Test
    fun `probe maps dead user-service and clipboard binder to unavailable`() {
        assertEquals(
            CapabilityState.UNAVAILABLE,
            ShizukuErrorCodes.probeReadState(ShizukuErrorCodes.USERSERVICE_DEAD),
        )
        assertEquals(
            CapabilityState.UNAVAILABLE,
            ShizukuErrorCodes.probeReadState(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD),
        )
    }

    @Test
    fun `probe maps shizuku binder death and api mismatch to unavailable`() {
        assertEquals(
            CapabilityState.UNAVAILABLE,
            ShizukuErrorCodes.probeReadState(ShizukuErrorCodes.BINDER_DEAD),
        )
        assertEquals(
            CapabilityState.UNAVAILABLE,
            ShizukuErrorCodes.probeReadState(ShizukuErrorCodes.API_MISMATCH),
        )
    }

    @Test
    fun `codes never look like clipboard payloads`() {
        assertTrue(ShizukuErrorCodes.ALL.none { it.contains(" ") || it.contains("\n") })
    }
}
