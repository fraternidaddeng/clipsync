package com.clipsync.android.service

/**
 * JVM-testable boot FGS health-check policy. [BootHealthCheckWorker] only
 * applies this decision; it must not retry past [ATTEMPT_CAP].
 */
object BootHealthCheck {
    const val ATTEMPT_CAP = 3

    fun shouldEnqueue(outcome: BootOutcome): Boolean = outcome != BootOutcome.Ignored

    fun decide(
        runAttemptCount: Int,
        processState: ServiceProcessState,
        wantedRunning: Boolean = true,
    ): BootHealthCheckDecision {
        if (processState == ServiceProcessState.RUNNING || !wantedRunning) {
            return BootHealthCheckDecision.SUCCESS
        }
        return if (runAttemptCount < ATTEMPT_CAP) {
            BootHealthCheckDecision.RETRY
        } else {
            BootHealthCheckDecision.REQUEST_RECOVERY
        }
    }
}

enum class BootHealthCheckDecision {
    SUCCESS,
    RETRY,
    REQUEST_RECOVERY,
}
