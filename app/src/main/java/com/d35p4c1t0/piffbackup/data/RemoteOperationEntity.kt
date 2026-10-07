package com.d35p4c1t0.piffbackup.data

import androidx.room3.*

@Entity(tableName = "remote_operations", foreignKeys = [ForeignKey(
    entity = StorageBoxProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"],
    onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class RemoteOperationEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val mappingId: String?,
    val kind: String,
    val remotePath: String,
    val localDestination: String?,
    val status: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val comparedFiles: Long,
    val changedFiles: Long,
    val errorCode: String?,
)
