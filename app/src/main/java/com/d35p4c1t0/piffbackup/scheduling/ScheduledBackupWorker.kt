package com.d35p4c1t0.piffbackup.scheduling

import android.content.Context
import androidx.work.*
import com.d35p4c1t0.piffbackup.PiffBackupApp
import com.d35p4c1t0.piffbackup.onboarding.OnboardingRequest
import java.util.concurrent.TimeUnit

/** Discovery is scheduled separately; a durable transfer is enqueued as ordinary constrained work. */
class ScheduledBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PiffBackupApp
        app.awaitReady()
        if (!app.backupPreferences.scheduled) return Result.success()
        val profileId = OnboardingRequest.DEFAULT_PROFILE_ID
        val profile = app.configurationStore.profile(profileId) ?: return Result.success()
        if (!profile.setupCompleted) return Result.success()
        if (app.database.dao().activeOperations().isNotEmpty()) return Result.success()
        when (val result = app.incrementalBackupCoordinator.discover(profileId)) {
            is BackupDiscoveryResult.Ready -> enqueue(app, result.pending.job.id)
            BackupDiscoveryResult.UpToDate -> Unit
            BackupDiscoveryResult.RequiresReconciliation -> {
                // A reset needs a reviewed foreground reconciliation, never silent size-only adoption.
                app.backgroundMessages.attention()
                return Result.success()
            }
            BackupDiscoveryResult.Failed -> {
                app.backgroundMessages.attention()
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
        return Result.success()
    }

    private fun enqueue(app: PiffBackupApp, jobId: String) {
        val request = OneTimeWorkRequestBuilder<PiffBackupWorker>()
            .setInputData(workDataOf(BackupScheduler.JOB_ID_KEY to jobId))
            .setConstraints(constraints(app)).build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            BackupScheduler.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        private const val NAME = "scheduled-backup-discovery"
        fun constraints(app: PiffBackupApp): Constraints {
            val p = app.backupPreferences
            return Constraints.Builder().setRequiredNetworkType(
                if (p.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(p.charging).setRequiresBatteryNotLow(p.batteryNotLow).build()
        }
        fun update(app: PiffBackupApp) {
            val work = WorkManager.getInstance(app)
            if (!app.backupPreferences.scheduled) { work.cancelUniqueWork(NAME); return }
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ScheduledBackupWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints(app)).build())
        }
    }
}
