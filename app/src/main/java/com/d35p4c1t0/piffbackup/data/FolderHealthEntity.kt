package com.d35p4c1t0.piffbackup.data

import androidx.room3.*

@Entity(tableName = "folder_health", foreignKeys = [ForeignKey(
    entity = FolderMappingEntity::class, parentColumns = ["id"], childColumns = ["mappingId"],
    onDelete = ForeignKey.CASCADE)])
data class FolderHealthEntity(
    @PrimaryKey val mappingId: String,
    val lastCheckedAtEpochMillis: Long?,
    val lastBackupAtEpochMillis: Long?,
    val lastVerifiedAtEpochMillis: Long?,
    val verificationScope: String?,
)
