package com.clipsync.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootHealthCheckTest {
    @Test
    fun `attempt below cap and not running retries`() {
        for (attempt in 0 until BootHealthCheck.ATTEMPT_CAP) {
            assertEquals(
                "attempt $attempt must retry",
                BootHealthCheckDecision.RETRY,
                BootHealthCheck.decide(
                    runAttemptCount = attempt,
                    processState = ServiceProcessState.STARTING,
                ),
            )
        }
    }

    @Test
    fun `attempt at or above cap and not running requests recovery`() {
        for (attempt in BootHealthCheck.ATTEMPT_CAP..(BootHealthCheck.ATTEMPT_CAP + 4)) {
            assertEquals(
                "attempt $attempt must request recovery, never retry",
                BootHealthCheckDecision.REQUEST_RECOVERY,
                BootHealthCheck.decide(
                    runAttemptCount = attempt,
                    processState = ServiceProcessState.STOPPED,
                ),
            )
        }
    }

    @Test
    fun `running is success at any attempt count`() {
        for (attempt in 0..8) {
            assertEquals(
                BootHealthCheckDecision.SUCCESS,
                BootHealthCheck.decide(
                    runAttemptCount = attempt,
                    processState = ServiceProcessState.RUNNING,
                ),
            )
        }
    }

    @Test
    fun `cap is three so WorkManager cannot loop unbounded`() {
        assertEquals(3, BootHealthCheck.ATTEMPT_CAP)
        assertEquals(
            BootHealthCheckDecision.RETRY,
            BootHealthCheck.decide(2, ServiceProcessState.ERROR),
        )
        assertEquals(
            BootHealthCheckDecision.REQUEST_RECOVERY,
            BootHealthCheck.decide(3, ServiceProcessState.ERROR),
        )
    }

    @Test
    fun `health check is enqueued after a boot-start attempt only`() {
        assertFalse(BootHealthCheck.shouldEnqueue(BootOutcome.Ignored))
        assertTrue(BootHealthCheck.shouldEnqueue(BootOutcome.Started))
        assertTrue(BootHealthCheck.shouldEnqueue(BootOutcome.RequestUserRecovery))
    }

    @Test
    fun `no longer wanted running succeeds instead of requesting recovery`() {
        assertEquals(
            BootHealthCheckDecision.SUCCESS,
            BootHealthCheck.decide(
                runAttemptCount = BootHealthCheck.ATTEMPT_CAP,
                processState = ServiceProcessState.STOPPED,
                wantedRunning = false,
            ),
        )
    }
}
