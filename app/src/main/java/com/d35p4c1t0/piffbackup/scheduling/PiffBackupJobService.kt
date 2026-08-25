package com.d35p4c1t0.piffbackup.scheduling

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import com.d35p4c1t0.piffbackup.PiffBackupApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PiffBackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val jobId = params.extras.getString(BackupScheduler.JOB_ID_KEY) ?: return false
        setNotification(
            params,
            BackupNotifications.NOTIFICATION_ID,
            BackupNotifications.active(this, jobId, 0),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val app = application as PiffBackupApp
        task = scope.launch {
            val result = app.backupExecutor.execute(jobId) { event ->
                if (event.status == BackupProgressStatus.RUNNING) {
                    BackupNotifications.update(this@PiffBackupJobService, jobId, event.percentage)
                }
            }
            jobFinished(params, result == BackupExecutionResult.RETRY)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val jobId = params.extras.getString(BackupScheduler.JOB_ID_KEY) ?: return false
        val app = application as PiffBackupApp
        val explicitlyPaused = app.backupExecutor.wasExplicitlyPaused(jobId)
        app.backupExecutor.requestStop(jobId, explicitPause = explicitlyPaused)
        task?.cancel()
        task = null
        return !explicitlyPaused
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
