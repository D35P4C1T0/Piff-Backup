package com.d35p4c1t0.piffbackup.scheduling

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.d35p4c1t0.piffbackup.PiffBackupApp
import kotlinx.coroutines.CancellationException

class PiffBackupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val jobId = requireNotNull(inputData.getString(BackupScheduler.JOB_ID_KEY))

    override suspend fun doWork(): Result {
        setForeground(
            ForegroundInfo(
                BackupNotifications.NOTIFICATION_ID,
                BackupNotifications.active(applicationContext, jobId, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ),
        )
        val app = applicationContext as PiffBackupApp
        return try {
            when (
                app.backupExecutor.execute(jobId) { event ->
                    if (event.status == BackupProgressStatus.RUNNING && event.fileName == null) {
                        BackupNotifications.update(applicationContext, jobId, event.percentage)
                    }
                }
            ) {
                BackupExecutionResult.SUCCEEDED -> Result.success()
                BackupExecutionResult.RETRY -> Result.retry()
                BackupExecutionResult.FAILED -> Result.failure()
                BackupExecutionResult.PAUSED -> Result.failure()
            }
        } catch (cancelled: CancellationException) {
            app.backupExecutor.requestStop(jobId, explicitPause = false)
            applicationContext.getSystemService(NotificationManager::class.java)
                .cancel(BackupNotifications.NOTIFICATION_ID)
            throw cancelled
        }
    }
}
