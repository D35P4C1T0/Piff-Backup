package com.d35p4c1t0.piffbackup.scheduling

import android.os.Environment
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataPlanner
import com.d35p4c1t0.piffbackup.allfiles.PlannedAllFilesTransfer
import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.DurablePendingJob
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.data.PendingBackupJobDraft
import com.d35p4c1t0.piffbackup.data.PendingRootDraft
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
    private val allFiles: AllFilesMetadataPlanner,
    private val volumeRoot: File = Environment.getExternalStorageDirectory(),
) {
    suspend fun discover(profileId: String): BackupDiscoveryResult {
        durableBackup.activeJob(profileId)?.let { return BackupDiscoveryResult.Ready(it) }
        val profile = configuration.profile(profileId) ?: return BackupDiscoveryResult.Failed
        val mappings = configuration.mappings(profileId).filter { it.enabled }
        if (mappings.isEmpty()) return BackupDiscoveryResult.Failed
        var mediaPlan: MediaPlanningResult.Incremental? = null
        var allFilesPlans: List<PlannedAllFilesTransfer> = emptyList()
        return try {
            val mediaEntities = mappings.filter { it.mode == MappingModeValue.MEDIA_FAST }
            val mediaMappingsById = mediaEntities.associate { entity ->
                entity.id to MediaStoreMapping.create(
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
                ).plan(PRIMARY_VOLUME, checkpoint, mediaMappingsById.values.toList())
            ) {
                is MediaPlanningResult.FullReconciliationRequired -> {
                    BackupDiscoveryResult.RequiresReconciliation
                }

                is MediaPlanningResult.Incremental -> {
                    mediaPlan = result
                    allFilesPlans = mappings.filter { it.mode == MappingModeValue.ALL_FILES }
                        .map { allFiles.plan(it) }

                    allFilesPlans.filter { it.itemCount == 0L }.forEach { plan ->
                        durableBackup.applyLocalMetadataSnapshot(plan.mapping.id, plan.fileList.path)
                        durableBackup.deleteLocalMetadataSnapshot(plan.fileList.path)
                        check(plan.fileList.delete() || !plan.fileList.exists())
                    }

                    val mediaDraftsByMapping = result.transfers.associate { transfer ->
                        val entity = mediaMappingsById.entries.single { it.value === transfer.mapping }.key
                        entity to PendingRootDraft(
                            folderMappingId = entity,
                            fileListPath = transfer.fileList.path,
                            totalFiles = transfer.itemCount,
                            totalBytes = transfer.totalBytes,
                        )
                    }
                    val allFilesDraftsByMapping = allFilesPlans.filter { it.itemCount > 0L }.associate { plan ->
                        plan.mapping.id to PendingRootDraft(
                            folderMappingId = plan.mapping.id,
                            fileListPath = plan.fileList.path,
                            totalFiles = plan.itemCount,
                            totalBytes = plan.totalBytes,
                        )
                    }
                    val roots = mappings.mapNotNull { entity ->
                        mediaDraftsByMapping[entity.id] ?: allFilesDraftsByMapping[entity.id]
                    }
                    if (roots.isEmpty()) {
                        durableBackup.establishCheckpoint(profileId, result.proposedCheckpoint)
                        BackupDiscoveryResult.UpToDate
                    } else {
                        BackupDiscoveryResult.Ready(
                            durableBackup.persistPendingJob(
                                PendingBackupJobDraft(
                                    id = UUID.randomUUID().toString(),
                                    profileId = profileId,
                                    volumeName = result.snapshot.volumeName,
                                    mediaStoreVersion = result.snapshot.version,
                                    configurationRevision = profile.configurationRevision,
                                    previousGeneration = result.window.afterExclusive,
                                    targetGeneration = result.window.throughInclusive,
                                    roots = roots,
                                ),
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {
            mediaPlan?.transfers?.forEach { transfer -> runCatching { transfer.fileList.delete() } }
            allFilesPlans.forEach { plan ->
                runCatching { durableBackup.deleteLocalMetadataSnapshot(plan.fileList.path) }
                runCatching { plan.fileList.delete() }
            }
            BackupDiscoveryResult.Failed
        }
    }

    private companion object {
        const val PRIMARY_VOLUME = "external_primary"
    }
}
