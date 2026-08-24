package com.clipsync.android.sync

/**
 * JVM-testable policy pieces of the boot restore chain (plan 5.2). The receiver and the
 * WorkManager worker only apply these decisions; neither may loop or invent state.
 */
object BootRestorePolicy {
    /**
     * One start attempt happens only when all three facts hold: the broadcast really is
     * BOOT_COMPLETED, the user explicitly enabled 开机恢复, and a paired peer exists. The
     * preference is re-read at boot time — the manifest component being enabled is never
     * trusted on its own (an app restore can carry a stale component state).
     */
    fun shouldAttemptStart(
        isBootAction: Boolean,
        bootRestoreEnabled: Boolean,
        paired: Boolean,
    ): Boolean = isBootAction && bootRestoreEnabled && paired
}

/**
 * Bounded post-boot health check: the receiver made its single start attempt; this policy
 * decides whether the service actually came up. It never restarts the service — after
 * [ATTEMPT_CAP] observations it requests user recovery and stops (plan 5.2: 不能崩溃、
 * 无限重启或静默失效).
 */
object BootHealthCheck {
    const val ATTEMPT_CAP = 3

    fun decide(
        runAttemptCount: Int,
        serviceRunning: Boolean,
        stillWanted: Boolean,
    ): Decision = when {
        serviceRunning -> Decision.HEALTHY
        // The user unpaired or turned 开机恢复 off since boot; nothing to recover.
        !stillWanted -> Decision.HEALTHY
        runAttemptCount < ATTEMPT_CAP -> Decision.CHECK_AGAIN
        else -> Decision.REQUEST_RECOVERY
    }

    enum class Decision {
        HEALTHY,
        CHECK_AGAIN,
        REQUEST_RECOVERY,
    }
}
