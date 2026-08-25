package com.d35p4c1t0.piffbackup.scheduling

import android.os.Environment
import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.DurablePendingJob
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.media.IncrementalMediaPlanner
import com.d35p4c1t0.piffbackup.media.MediaPlanningResult
import com.d35p4c1t0.piffbackup.media.MediaStoreMapping
import com.d35p4c1t0.piffbackup.media.MediaStoreSource
import java.io.File
import java.util.UUID

sealed interface BackupDiscoveryResult {
    data class Ready(val pending: DurablePendingJob) : BackupDiscoveryResult
    data object UpToDate : BackupDiscoveryResult
    data object RequiresReconciliation : BackupDiscoveryResult
    data object Failed : BackupDiscoveryResult
}

class IncrementalBackupCoordinator(
    private val configuration: DurableConfigurationStore,
    private val durableBackup: DurableBackupStore,
    private val mediaSource: MediaStoreSource,
    private val fileLists: IncrementalFileListStore,
    private val volumeRoot: File = Environment.getExternalStorageDirectory(),
) {
    suspend fun discover(profileId: String): BackupDiscoveryResult {
        durableBackup.activeJob(profileId)?.let { return BackupDiscoveryResult.Ready(it) }
        val profile = configuration.profile(profileId) ?: return BackupDiscoveryResult.Failed
        val mappings = configuration.mappings(profileId).filter { it.enabled }
        if (mappings.isEmpty()) return BackupDiscoveryResult.Failed
        if (mappings.any { it.mode != MappingModeValue.MEDIA_FAST }) {
            return BackupDiscoveryResult.RequiresReconciliation
        }
        var plan: MediaPlanningResult.Incremental? = null
        return try {
            val mediaMappings = mappings.map { entity ->
                MediaStoreMapping.create(
                    mapping = BackupMapping(
                        localRoot = CanonicalLocalRoot.create(entity.canonicalLocalPath, volumeRoot),
                        remoteRoot = RemoteRelativePath.create(entity.relativeRemotePath),
                    ),
                    volumeRoot = volumeRoot,
                )
            }
            val checkpoint = durableBackup.checkpointForPlanning(profileId, PRIMARY_VOLUME)
            when (
                val result = IncrementalMediaPlanner(
                    source = mediaSource,
                    fileListStore = fileLists,
                    requiredRemoteBase = RemoteRelativePath.create(profile.remoteBasePath),
                ).plan(PRIMARY_VOLUME, checkpoint, mediaMappings)
            ) {
                is MediaPlanningResult.FullReconciliationRequired -> {
                    BackupDiscoveryResult.RequiresReconciliation
                }

                is MediaPlanningResult.Incremental -> {
                    plan = result
                    if (result.hasWork) {
                        BackupDiscoveryResult.Ready(
                            durableBackup.persistIncrementalPlan(
                                jobId = UUID.randomUUID().toString(),
                                profileId = profileId,
                                plan = result,
                            ),
                        )
                    } else {
                        durableBackup.establishCheckpoint(profileId, result.proposedCheckpoint)
                        BackupDiscoveryResult.UpToDate
                    }
                }
            }
        } catch (_: Exception) {
            plan?.transfers?.forEach { transfer -> runCatching { transfer.fileList.delete() } }
            BackupDiscoveryResult.Failed
        }
    }

    private companion object {
        const val PRIMARY_VOLUME = "external_primary"
    }
}
