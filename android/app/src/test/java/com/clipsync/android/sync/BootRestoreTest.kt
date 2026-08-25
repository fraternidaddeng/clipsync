package com.clipsync.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRestorePolicyTest {

    @Test
    fun `start is attempted only when action, preference and pairing all hold`() {
        assertTrue(
            BootRestorePolicy.shouldAttemptStart(
                isBootAction = true,
                bootRestoreEnabled = true,
                paired = true,
            ),
        )
    }

    @Test
    fun `a foreign broadcast action never starts anything`() {
        assertFalse(
            BootRestorePolicy.shouldAttemptStart(
                isBootAction = false,
                bootRestoreEnabled = true,
                paired = true,
            ),
        )
    }

    @Test
    fun `the preference gates the receiver even if the component was left enabled`() {
        assertFalse(
            BootRestorePolicy.shouldAttemptStart(
                isBootAction = true,
                bootRestoreEnabled = false,
                paired = true,
            ),
        )
    }

    @Test
    fun `no pairing means nothing to restore`() {
        assertFalse(
            BootRestorePolicy.shouldAttemptStart(
                isBootAction = true,
                bootRestoreEnabled = true,
                paired = false,
            ),
        )
    }
}

class BootHealthCheckTest {

    @Test
    fun `a running service is healthy on the first observation`() {
        assertEquals(
            BootHealthCheck.Decision.HEALTHY,
            BootHealthCheck.decide(runAttemptCount = 0, serviceRunning = true, stillWanted = true),
        )
    }

    @Test
    fun `a stopped service is re-checked while under the attempt cap`() {
        for (attempt in 0 until BootHealthCheck.ATTEMPT_CAP) {
            assertEquals(
                BootHealthCheck.Decision.CHECK_AGAIN,
                BootHealthCheck.decide(
                    runAttemptCount = attempt,
                    serviceRunning = false,
                    stillWanted = true,
                ),
            )
        }
    }

    @Test
    fun `the cap converts to a recovery request, never another retry`() {
        assertEquals(
            BootHealthCheck.Decision.REQUEST_RECOVERY,
            BootHealthCheck.decide(
                runAttemptCount = BootHealthCheck.ATTEMPT_CAP,
                serviceRunning = false,
                stillWanted = true,
            ),
        )
        // Bounded even if the worker somehow runs beyond the cap.
        assertEquals(
            BootHealthCheck.Decision.REQUEST_RECOVERY,
            BootHealthCheck.decide(
                runAttemptCount = BootHealthCheck.ATTEMPT_CAP + 5,
                serviceRunning = false,
                stillWanted = true,
            ),
        )
    }

    @Test
    fun `an unpaired or opted-out user ends the check quietly`() {
        assertEquals(
            BootHealthCheck.Decision.HEALTHY,
            BootHealthCheck.decide(runAttemptCount = 1, serviceRunning = false, stillWanted = false),
        )
    }
}
