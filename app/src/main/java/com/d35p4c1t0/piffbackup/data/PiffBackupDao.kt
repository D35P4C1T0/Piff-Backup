package com.d35p4c1t0.piffbackup.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert

@Dao
interface PiffBackupDao {
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
        WHERE status IN ('PLANNED', 'RUNNING', 'PAUSED', 'RETRYABLE')
        ORDER BY createdAtEpochMillis, id
        """,
    )
    suspend fun activeJobs(): List<PendingBackupJobEntity>

    @Query(
        """
        SELECT * FROM pending_backup_jobs
        WHERE profileId = :profileId
          AND status IN ('PLANNED', 'RUNNING', 'PAUSED', 'RETRYABLE')
        ORDER BY createdAtEpochMillis, id
        """,
    )
    suspend fun activeJobs(profileId: String): List<PendingBackupJobEntity>

    @Query("SELECT * FROM pending_backup_jobs WHERE status = 'SUCCEEDED' ORDER BY updatedAtEpochMillis, id")
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

    @Query("DELETE FROM all_files_metadata WHERE folderMappingId = :mappingId")
    suspend fun deleteLocalMetadata(mappingId: String): Int
}
