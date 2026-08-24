package com.clipsync.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.notify.SyncNotifications
import com.clipsync.android.storage.SyncSettingsStore
import java.util.concurrent.TimeUnit

/**
 * Thin WorkManager shell around [BootHealthCheck]: after the boot receiver's single start
 * attempt, verify the foreground service actually reached RUNNING. Bounded by
 * [BootHealthCheck.ATTEMPT_CAP] via WorkManager's own [runAttemptCount]; it observes and
 * notifies, it never restarts the service (plan 5.2).
 */
class BootHealthCheckWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        return try {
            val settings = SyncSettingsStore(
                SharedPrefsKeyValueStore(app, name = SyncSettingsStore.PREFERENCES_NAME),
            )
            val paired = PairingStore(SharedPrefsKeyValueStore(app), KeystoreSecretProtector())
                .peer() != null
            when (
                BootHealthCheck.decide(
                    runAttemptCount = runAttemptCount,
                    serviceRunning = ClipboardSyncService.serviceRunning.value,
                    stillWanted = settings.bootRestoreEnabled && paired,
                )
            ) {
                BootHealthCheck.Decision.HEALTHY -> Result.success()
                BootHealthCheck.Decision.CHECK_AGAIN -> Result.retry()
                BootHealthCheck.Decision.REQUEST_RECOVERY -> {
                    SyncNotifications.notifyRecoveryNeeded(app)
                    Result.failure()
                }
            }
        } catch (_: Exception) {
            // A broken health check must not crash-loop the boot chain.
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "clipsync.boot_health_check"
        const val INITIAL_DELAY_SECONDS = 15L

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
    }
}
