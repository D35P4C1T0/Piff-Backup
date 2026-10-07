package com.d35p4c1t0.piffbackup.scheduling

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.d35p4c1t0.piffbackup.data.DurableBackupStore

class BackupScheduler(
    private val context: Context,
    private val durableBackup: DurableBackupStore,
    private val executor: BackupExecutor,
    private val preferences: com.d35p4c1t0.piffbackup.settings.BackupPreferences =
        com.d35p4c1t0.piffbackup.settings.BackupPreferences(context),
) {
    suspend fun schedule(jobId: String, uploadBytes: Long): Boolean {
        if (jobId.startsWith("operation-")) {
            (context.applicationContext as com.d35p4c1t0.piffbackup.PiffBackupApp).remoteOperations.clearStop(jobId)
        } else executor.clearStop(jobId)
        val scheduled = runCatching { if (Build.VERSION.SDK_INT >= 34) {
            val extras = PersistableBundle().apply { putString(JOB_ID_KEY, jobId) }
            val info = JobInfo.Builder(
                NATIVE_JOB_ID,
                ComponentName(context, PiffBackupJobService::class.java),
            )
                .setRequiredNetworkType(if (preferences.unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(preferences.charging)
                .setRequiresBatteryNotLow(preferences.batteryNotLow)
                .setEstimatedNetworkBytes(0L, uploadBytes.coerceAtLeast(0L))
                .setUserInitiated(true)
                .setExtras(extras)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS
        } else {
            val request = OneTimeWorkRequestBuilder<PiffBackupWorker>()
                .setInputData(Data.Builder().putString(JOB_ID_KEY, jobId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (preferences.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresCharging(preferences.charging)
                        .setRequiresBatteryNotLow(preferences.batteryNotLow)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            true
        } }.getOrDefault(false)
        if (scheduled) {
            if (jobId.startsWith("operation-")) {
                val dao = (context.applicationContext as com.d35p4c1t0.piffbackup.PiffBackupApp).database.dao()
                dao.operation(jobId)?.takeIf { it.status in setOf("PLANNED", "PAUSED") }?.let { dao.saveOperation(it.copy(status = "QUEUED")) }
            } else durableBackup.markQueued(jobId)
        }
        return scheduled
    }

    suspend fun pause(jobId: String) {
        val operation = jobId.startsWith("operation-")
        val app = context.applicationContext as com.d35p4c1t0.piffbackup.PiffBackupApp
        if (operation) app.remoteOperations.stop(jobId) else executor.requestStop(jobId, explicitPause = true)
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(JobScheduler::class.java).cancel(NATIVE_JOB_ID)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
        if (operation) app.remoteOperations.pause(jobId) else {
            executor.awaitStopped()
            durableBackup.markInterrupted(jobId, paused = true)
            durableBackup.markJobPaused(jobId)
        }
    }

    companion object {
        const val JOB_ID_KEY = "durable_job_id"
        const val UNIQUE_WORK_NAME = "global-manual-backup"
        const val NATIVE_JOB_ID = 41002
    }
}
