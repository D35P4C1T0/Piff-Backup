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
) {
    fun schedule(jobId: String, uploadBytes: Long): Boolean {
        executor.clearStop(jobId)
        return runCatching { if (Build.VERSION.SDK_INT >= 34) {
            val extras = PersistableBundle().apply { putString(JOB_ID_KEY, jobId) }
            val info = JobInfo.Builder(
                NATIVE_JOB_ID,
                ComponentName(context, PiffBackupJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
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
                        .setRequiredNetworkType(NetworkType.CONNECTED)
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
    }

    suspend fun pause(jobId: String) {
        executor.requestStop(jobId, explicitPause = true)
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(JobScheduler::class.java).cancel(NATIVE_JOB_ID)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
        durableBackup.markJobPaused(jobId)
    }

    companion object {
        const val JOB_ID_KEY = "durable_job_id"
        const val UNIQUE_WORK_NAME = "global-manual-backup"
        const val NATIVE_JOB_ID = 41002
    }
}
