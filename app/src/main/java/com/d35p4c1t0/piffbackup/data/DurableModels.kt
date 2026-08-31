package com.d35p4c1t0.piffbackup.data

import java.util.UUID

fun interface EpochMillisClock {
    fun now(): Long
}

fun interface DurableIdGenerator {
    fun next(): String
}

object SystemEpochMillisClock : EpochMillisClock {
    override fun now(): Long = System.currentTimeMillis()
}

object UuidDurableIdGenerator : DurableIdGenerator {
    override fun next(): String = UUID.randomUUID().toString()
}

data class StorageBoxProfileInput(
    val id: String,
    val username: String,
    val hostname: String,
    val remoteBasePath: String,
    val port: Int = 23,
    val encryptedCredentialRef: String? = null,
    val pinnedHostKey: String? = null,
    val setupCompleted: Boolean = false,
)

data class FolderMappingInput(
    val id: String,
    val displayName: String,
    val treeUri: String,
    val canonicalLocalPath: String,
    val relativeMediaStorePrefix: String,
    val relativeRemotePath: String,
    val mode: String,
    val enabled: Boolean = true,
)

data class PendingRootDraft(
    val folderMappingId: String,
    val fileListPath: String,
    val totalFiles: Long,
    val totalBytes: Long,
)

data class PendingBackupJobDraft(
    val id: String,
    val profileId: String,
    val volumeName: String,
    val mediaStoreVersion: String,
    val configurationRevision: Long,
    val previousGeneration: Long,
    val targetGeneration: Long,
    val roots: List<PendingRootDraft>,
)

enum class RootExecutionOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    CANCELLED,
}

data class DurablePendingJob(
    val job: PendingBackupJobEntity,
    val roots: List<PendingRootWorkEntity>,
)

object DurableErrorCode {
    const val CONFIGURATION_CHANGED = "CONFIGURATION_CHANGED"
    const val CHECKPOINT_CHANGED = "CHECKPOINT_CHANGED"
    const val FILE_LIST_MISSING = "FILE_LIST_MISSING"
    const val METADATA_SNAPSHOT_MISSING = "METADATA_SNAPSHOT_MISSING"
    const val CORRUPT_STATE = "CORRUPT_STATE"
    const val TRANSFER_FAILED = "TRANSFER_FAILED"
}
