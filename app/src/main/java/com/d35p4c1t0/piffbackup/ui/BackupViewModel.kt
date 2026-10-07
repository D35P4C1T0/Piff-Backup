package com.d35p4c1t0.piffbackup.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.d35p4c1t0.piffbackup.PiffBackupApp
import com.d35p4c1t0.piffbackup.data.*
import kotlinx.coroutines.flow.*

/** Navigation-independent home state, reloaded from Room rather than transient progress events. */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PiffBackupApp
    val changes: Flow<Unit> = flow {
        app.awaitReady()
        val dao = app.database.dao()
        emitAll(combine(dao.observeJobs("primary"), dao.observeOperations("primary"),
            dao.observeHistory("primary"), dao.observeCheckpoints("primary"), dao.observeHealth("primary")) {
                _, _, _, _, _ -> Unit
            })
    }

    suspend fun snapshot(): BackupSnapshot {
        app.awaitReady()
        val profile = app.configurationStore.profile("primary")
        val mappings = profile?.let { app.configurationStore.mappings(it.id) }.orEmpty()
        val checkpoint = profile?.let { app.durableBackupStore.checkpointForPlanning(it.id, "external_primary") }
        val successful = profile?.let { app.durableBackupStore.latestSuccessfulRun(it.id) }
        val pending = profile?.let { app.durableBackupStore.activeJob(it.id) }
        val problem = profile?.let { app.durableBackupStore.latestProblemJob(it.id) }
        val operations = profile?.let { app.database.dao().operations(it.id) }.orEmpty()
        val operation = operations.firstOrNull { it.status in setOf("PLANNED", "QUEUED", "RUNNING", "PAUSED") }
            ?: operations.firstOrNull()?.takeIf { it.status == "FAILED" &&
                (it.finishedAtEpochMillis ?: 0L) > (successful?.finishedAtEpochMillis ?: 0L) }
        return BackupSnapshot(profile, mappings, checkpoint != null, successful?.finishedAtEpochMillis,
            pending, problem, operation)
    }
}

data class BackupSnapshot(
    val profile: StorageBoxProfileEntity?, val mappings: List<FolderMappingEntity>,
    val hasCheckpoint: Boolean, val lastSuccessfulBackupAtEpochMillis: Long?,
    val pending: DurablePendingJob?, val problem: PendingBackupJobEntity?, val operation: RemoteOperationEntity?,
)
