package com.d35p4c1t0.piffbackup.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert

@Dao
interface PiffBackupDao {
    @Query("DELETE FROM folder_health WHERE mappingId = :mappingId")
    suspend fun deleteFolderHealth(mappingId: String)

    @Query("SELECT * FROM backup_selection WHERE profileId = :profileId")
    suspend fun selectionPolicy(profileId: String): BackupSelectionEntity?
    @Upsert suspend fun saveSelectionPolicy(policy: BackupSelectionEntity)

    @Query("SELECT * FROM folder_health WHERE mappingId = :mappingId")
    suspend fun folderHealth(mappingId: String): FolderHealthEntity?
    @Upsert
    suspend fun saveFolderHealth(health: FolderHealthEntity)
    @Query("DELETE FROM remote_operations WHERE profileId = :profileId AND status IN ('SUCCEEDED', 'SUPERSEDED') AND id NOT IN (SELECT id FROM remote_operations WHERE profileId = :profileId ORDER BY startedAtEpochMillis DESC LIMIT 100)")
    suspend fun pruneOperations(profileId: String)

    @Query("SELECT * FROM backup_health WHERE profileId = :profileId")
    fun observeHealth(profileId: String): kotlinx.coroutines.flow.Flow<BackupHealthEntity?>
    @Query("SELECT * FROM media_checkpoints WHERE profileId = :profileId")
    fun observeCheckpoints(profileId: String): kotlinx.coroutines.flow.Flow<List<MediaCheckpointEntity>>
    @Query("SELECT * FROM backup_runs WHERE profileId = :profileId ORDER BY finishedAtEpochMillis DESC LIMIT 100")
    fun observeHistory(profileId: String): kotlinx.coroutines.flow.Flow<List<BackupRunEntity>>

    @Upsert suspend fun saveOperation(operation: RemoteOperationEntity)
    @Query("SELECT * FROM remote_operations WHERE id = :id")
    suspend fun operation(id: String): RemoteOperationEntity?
    @Query("SELECT * FROM remote_operations WHERE profileId = :profileId ORDER BY startedAtEpochMillis DESC LIMIT 100")
    suspend fun operations(profileId: String): List<RemoteOperationEntity>
    @Query("SELECT * FROM remote_operations WHERE status IN ('PLANNED', 'QUEUED', 'RUNNING', 'PAUSED')")
    suspend fun activeOperations(): List<RemoteOperationEntity>
    @Query("UPDATE remote_operations SET status = 'PAUSED' WHERE status = 'RUNNING'")
    suspend fun recoverOperations()
    @Query("SELECT * FROM remote_operations WHERE profileId = :profileId ORDER BY startedAtEpochMillis DESC")
    fun observeOperations(profileId: String): kotlinx.coroutines.flow.Flow<List<RemoteOperationEntity>>

    @Query("SELECT * FROM backup_health WHERE profileId = :profileId")
    suspend fun health(profileId: String): BackupHealthEntity?

    @Upsert
    suspend fun saveHealth(health: BackupHealthEntity)

    @Query("SELECT * FROM backup_runs WHERE profileId = :profileId ORDER BY finishedAtEpochMillis DESC LIMIT 100")
    suspend fun history(profileId: String): List<BackupRunEntity>

    @Query("DELETE FROM backup_runs WHERE profileId = :profileId AND id NOT IN (SELECT id FROM backup_runs WHERE profileId = :profileId ORDER BY finishedAtEpochMillis DESC LIMIT 100)")
    suspend fun pruneHistory(profileId: String)

    @Query("SELECT * FROM pending_backup_jobs WHERE profileId = :profileId ORDER BY createdAtEpochMillis DESC")
    fun observeJobs(profileId: String): kotlinx.coroutines.flow.Flow<List<PendingBackupJobEntity>>

    @Query("DELETE FROM storage_box_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Insert
    suspend fun insertProfile(profile: StorageBoxProfileEntity)

    @Update
    suspend fun updateProfile(profile: StorageBoxProfileEntity): Int

    @Query("SELECT * FROM storage_box_profiles WHERE id = :profileId")
    suspend fun profile(profileId: String): StorageBoxProfileEntity?

    @Query("SELECT * FROM storage_box_profiles ORDER BY createdAtEpochMillis, id")
    suspend fun profiles(): List<StorageBoxProfileEntity>

    @Insert
    suspend fun insertMappings(mappings: List<FolderMappingEntity>)

    @Upsert
    suspend fun upsertMappings(mappings: List<FolderMappingEntity>)

    @Query("DELETE FROM folder_mappings WHERE profileId = :profileId")
    suspend fun deleteMappings(profileId: String): Int

    @Query("DELETE FROM folder_mappings WHERE profileId = :profileId AND id NOT IN (:keptIds)")
    suspend fun deleteMappingsExcept(profileId: String, keptIds: List<String>): Int

    @Query("SELECT * FROM folder_mappings WHERE profileId = :profileId ORDER BY createdAtEpochMillis, id")
    suspend fun mappings(profileId: String): List<FolderMappingEntity>

    @Query("SELECT * FROM folder_mappings WHERE id IN (:mappingIds)")
    suspend fun mappingsById(mappingIds: List<String>): List<FolderMappingEntity>

    @Upsert
    suspend fun upsertCheckpoint(checkpoint: MediaCheckpointEntity)

    @Query("SELECT * FROM media_checkpoints WHERE profileId = :profileId AND volumeName = :volumeName")
    suspend fun checkpoint(profileId: String, volumeName: String): MediaCheckpointEntity?

    @Insert
    suspend fun insertJob(job: PendingBackupJobEntity)

    @Update
    suspend fun updateJob(job: PendingBackupJobEntity): Int

    @Query("SELECT * FROM pending_backup_jobs WHERE id = :jobId")
    suspend fun job(jobId: String): PendingBackupJobEntity?

    @Query(
        """
        SELECT * FROM pending_backup_jobs
        WHERE status IN ('PLANNED', 'QUEUED', 'RUNNING', 'PAUSED', 'RETRYABLE')
        ORDER BY createdAtEpochMillis, id
        """,
    )
    suspend fun activeJobs(): List<PendingBackupJobEntity>

    @Query(
        """
        SELECT * FROM pending_backup_jobs
        WHERE profileId = :profileId
          AND status IN ('PLANNED', 'QUEUED', 'RUNNING', 'PAUSED', 'RETRYABLE')
        ORDER BY createdAtEpochMillis, id
        """,
    )
    suspend fun activeJobs(profileId: String): List<PendingBackupJobEntity>

    @Query(
        """
        SELECT * FROM pending_backup_jobs
        WHERE profileId = :profileId AND status IN ('FAILED', 'NEEDS_RECONCILIATION')
        ORDER BY updatedAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun latestProblemJob(profileId: String): PendingBackupJobEntity?

    @Query("SELECT * FROM pending_backup_jobs WHERE status IN ('SUCCEEDED', 'SUPERSEDED') ORDER BY updatedAtEpochMillis, id")
    suspend fun completedJobsAwaitingCleanup(): List<PendingBackupJobEntity>

    @Query("SELECT * FROM pending_backup_jobs ORDER BY createdAtEpochMillis, id")
    suspend fun jobs(): List<PendingBackupJobEntity>

    @Insert
    suspend fun insertRootWork(roots: List<PendingRootWorkEntity>)

    @Update
    suspend fun updateRootWork(root: PendingRootWorkEntity): Int

    @Query("SELECT * FROM pending_root_work WHERE jobId = :jobId ORDER BY sequence, folderMappingId")
    suspend fun rootWork(jobId: String): List<PendingRootWorkEntity>

    @Query("SELECT * FROM pending_root_work WHERE jobId = :jobId AND folderMappingId = :mappingId")
    suspend fun rootWork(jobId: String, mappingId: String): PendingRootWorkEntity?

    @Query("DELETE FROM pending_backup_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBackupRun(run: BackupRunEntity)

    @Query("SELECT * FROM backup_runs WHERE id = :runId")
    suspend fun backupRun(runId: String): BackupRunEntity?

    @Query("SELECT * FROM backup_runs ORDER BY finishedAtEpochMillis, id")
    suspend fun backupRuns(): List<BackupRunEntity>

    @Query(
        """
        SELECT * FROM backup_runs
        WHERE profileId = :profileId AND result = 'SUCCEEDED'
        ORDER BY finishedAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun latestSuccessfulBackupRun(profileId: String): BackupRunEntity?

    @Upsert
    suspend fun upsertLocalMetadata(metadata: List<LocalFileMetadataEntity>)

    @Query(
        """
        SELECT * FROM all_files_metadata
        WHERE folderMappingId = :mappingId AND relativePath IN (:relativePaths)
        """,
    )
    suspend fun localMetadata(
        mappingId: String,
        relativePaths: List<String>,
    ): List<LocalFileMetadataEntity>

    @Query("DELETE FROM all_files_metadata WHERE folderMappingId = :mappingId")
    suspend fun deleteLocalMetadata(mappingId: String): Int
}
