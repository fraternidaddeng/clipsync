package com.clipsync.android.sync

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun `without jitter the delay doubles and caps`() {
        val backoff = ReconnectBackoff(initialDelayMs = 1_000, maxDelayMs = 60_000, jitterRatio = 0.0)
        assertEquals(1_000, backoff.nextDelayMs(0))
        assertEquals(2_000, backoff.nextDelayMs(1))
        assertEquals(4_000, backoff.nextDelayMs(2))
        assertEquals(32_000, backoff.nextDelayMs(5))
        assertEquals(60_000, backoff.nextDelayMs(6))
        assertEquals(60_000, backoff.nextDelayMs(50))
    }

    @Test
    fun `jitter stays inside the configured band`() {
        val backoff = ReconnectBackoff(
            initialDelayMs = 1_000,
            maxDelayMs = 60_000,
            jitterRatio = 0.2,
            random = Random(seed = 42),
        )
        repeat(200) {
            val delay = backoff.nextDelayMs(3)
            assertTrue("delay $delay outside band", delay in 6_400..9_600)
        }
    }

    @Test
    fun `huge attempt numbers do not overflow`() {
        val backoff = ReconnectBackoff(initialDelayMs = 1_000, maxDelayMs = 60_000, jitterRatio = 0.0)
        assertEquals(60_000, backoff.nextDelayMs(10_000))
    }
}
