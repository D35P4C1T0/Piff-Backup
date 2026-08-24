package com.d35p4c1t0.piffbackup.data

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(tableName = "storage_box_profiles")
data class StorageBoxProfileEntity(
    @androidx.room3.PrimaryKey
    val id: String,
    val username: String,
    val hostname: String,
    val port: Int,
    val remoteBasePath: String,
    val encryptedCredentialRef: String?,
    val pinnedHostKey: String?,
    val setupCompleted: Boolean,
    val configurationRevision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "folder_mappings",
    foreignKeys = [
        ForeignKey(
            entity = StorageBoxProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["profileId", "canonicalLocalPath"], unique = true),
        Index(value = ["profileId", "relativeRemotePath"], unique = true),
    ],
)
data class FolderMappingEntity(
    @androidx.room3.PrimaryKey
    val id: String,
    val profileId: String,
    val displayName: String,
    val treeUri: String,
    val canonicalLocalPath: String,
    val relativeMediaStorePrefix: String,
    val relativeRemotePath: String,
    val mode: String,
    val enabled: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "media_checkpoints",
    primaryKeys = ["profileId", "volumeName"],
    foreignKeys = [
        ForeignKey(
            entity = StorageBoxProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"])],
)
data class MediaCheckpointEntity(
    val profileId: String,
    val volumeName: String,
    val mediaStoreVersion: String,
    val successfulGeneration: Long,
    val configurationRevision: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "pending_backup_jobs",
    foreignKeys = [
        ForeignKey(
            entity = StorageBoxProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"]), Index(value = ["status"])],
)
data class PendingBackupJobEntity(
    @androidx.room3.PrimaryKey
    val id: String,
    val profileId: String,
    val volumeName: String,
    val mediaStoreVersion: String,
    val configurationRevision: Long,
    val previousGeneration: Long,
    val targetGeneration: Long,
    val status: String,
    val totalFiles: Long,
    val totalBytes: Long,
    val completedFiles: Long,
    val completedBytes: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sanitizedErrorCode: String?,
)

@Entity(
    tableName = "pending_root_work",
    primaryKeys = ["jobId", "folderMappingId"],
    foreignKeys = [
        ForeignKey(
            entity = PendingBackupJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["jobId"]), Index(value = ["status"])],
)
data class PendingRootWorkEntity(
    val jobId: String,
    val folderMappingId: String,
    val sequence: Int,
    val canonicalLocalPath: String,
    val relativeRemotePath: String,
    val fileListPath: String,
    val status: String,
    val totalFiles: Long,
    val totalBytes: Long,
    val completedFiles: Long,
    val completedBytes: Long,
    val rsyncExitCode: Int?,
    val sanitizedErrorCode: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "backup_runs",
    foreignKeys = [
        ForeignKey(
            entity = StorageBoxProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"]), Index(value = ["finishedAtEpochMillis"])],
)
data class BackupRunEntity(
    @androidx.room3.PrimaryKey
    val id: String,
    val profileId: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val result: String,
    val discoveredFiles: Long,
    val uploadedFiles: Long,
    val uploadedBytes: Long,
    val sanitizedErrorCode: String?,
)

@Entity(
    tableName = "all_files_metadata",
    primaryKeys = ["folderMappingId", "relativePath"],
    foreignKeys = [
        ForeignKey(
            entity = FolderMappingEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderMappingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["folderMappingId"])],
)
data class LocalFileMetadataEntity(
    val folderMappingId: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val observedAtEpochMillis: Long,
)

object MappingModeValue {
    const val MEDIA_FAST = "MEDIA_FAST"
    const val ALL_FILES = "ALL_FILES"
    val ALL = setOf(MEDIA_FAST, ALL_FILES)
}

object PendingJobStatusValue {
    const val PLANNED = "PLANNED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val RETRYABLE = "RETRYABLE"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION"
}

object PendingRootStatusValue {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val RETRYABLE = "RETRYABLE"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
}

object BackupRunResultValue {
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
}
