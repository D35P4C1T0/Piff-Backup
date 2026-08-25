package com.d35p4c1t0.piffbackup.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.d35p4c1t0.piffbackup.PiffBackupApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BackupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val jobId = intent.getStringExtra(BackupScheduler.JOB_ID_KEY) ?: return
        val pending = goAsync()
        val app = context.applicationContext as PiffBackupApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.backupScheduler.pause(jobId)
                BackupProgressEvents.publish(BackupProgressEvent(jobId, BackupProgressStatus.PAUSED, 0))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.d35p4c1t0.piffbackup.action.PAUSE_BACKUP"
    }
}
