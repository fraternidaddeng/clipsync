package com.clipsync.android.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Thin WorkManager shell around [BootHealthCheck]. Bounded by
 * [BootHealthCheck.ATTEMPT_CAP] via [runAttemptCount]; never crash-loops.
 */
class BootHealthCheckWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val orch = ClipboardSyncRuntime.orchestrator
            when (
                BootHealthCheck.decide(
                    runAttemptCount = runAttemptCount,
                    processState = orch.processState,
                    wantedRunning = orch.wantedRunning,
                )
            ) {
                BootHealthCheckDecision.SUCCESS -> Result.success()
                BootHealthCheckDecision.RETRY -> Result.retry()
                BootHealthCheckDecision.REQUEST_RECOVERY -> {
                    orch.onBootHealthCheckFailed()
                    BootRecoveryNotifier.request(applicationContext)
                    Result.failure()
                }
            }
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "clipsync.boot_health_check"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BootHealthCheckWorker>()
                .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        const val INITIAL_DELAY_SECONDS = 5L
    }
}
