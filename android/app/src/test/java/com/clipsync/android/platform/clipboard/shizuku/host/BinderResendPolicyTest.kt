package com.clipsync.android.platform.clipboard.shizuku.host

import org.junit.Assert.assertEquals
import org.junit.Test

class BinderResendPolicyTest {
    @Test
    fun `before any client attaches the hand-off is fast, then backs off`() {
        assertEquals(1_000L, BinderResendPolicy.nextDelayMillis(ticksSent = 1, clientAttached = false))
        assertEquals(1_000L, BinderResendPolicy.nextDelayMillis(ticksSent = 29, clientAttached = false))
        assertEquals(10_000L, BinderResendPolicy.nextDelayMillis(ticksSent = 30, clientAttached = false))
        assertEquals(10_000L, BinderResendPolicy.nextDelayMillis(ticksSent = 500, clientAttached = false))
    }

    @Test
    fun `an attached client turns the hand-off into a 30 s keepalive regardless of tick count`() {
        assertEquals(30_000L, BinderResendPolicy.nextDelayMillis(ticksSent = 1, clientAttached = true))
        assertEquals(30_000L, BinderResendPolicy.nextDelayMillis(ticksSent = 500, clientAttached = true))
    }

    @Test
    fun `a reset tick count after a client death is fast again`() {
        assertEquals(1_000L, BinderResendPolicy.nextDelayMillis(ticksSent = 0, clientAttached = false))
    }
}
