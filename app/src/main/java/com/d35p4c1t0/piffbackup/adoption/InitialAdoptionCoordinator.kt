package com.d35p4c1t0.piffbackup.adoption

import android.content.Context
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.FolderMappingEntity
import com.d35p4c1t0.piffbackup.data.FolderMappingInput
import com.d35p4c1t0.piffbackup.media.MediaAccessScope
import com.d35p4c1t0.piffbackup.media.MediaStoreCheckpoint
import com.d35p4c1t0.piffbackup.media.MediaStoreSnapshot
import com.d35p4c1t0.piffbackup.media.MediaStoreSource
import com.d35p4c1t0.piffbackup.onboarding.KnownHostStore
import com.d35p4c1t0.piffbackup.onboarding.OnboardingCredentialManager
import com.d35p4c1t0.piffbackup.rsync.AdoptionPreviewSummary
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.rsync.RsyncCommandBuilder
import com.d35p4c1t0.piffbackup.rsync.RsyncCommandEngine
import com.d35p4c1t0.piffbackup.rsync.RsyncExecutionResult
import com.d35p4c1t0.piffbackup.rsync.RsyncExitKind
import com.d35p4c1t0.piffbackup.rsync.RsyncProgress
import com.d35p4c1t0.piffbackup.rsync.RunningRsyncCommand
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import java.io.File
import java.util.UUID

data class AdoptionPreviewRoot(
    val files: InitialRootFileList,
    val summary: AdoptionPreviewSummary,
)

data class InitialAdoptionPreview(
    val id: String,
    val profileId: String,
    val configurationRevision: Long,
    val snapshot: MediaStoreSnapshot,
    val roots: List<AdoptionPreviewRoot>,
    val summary: AdoptionPreviewSummary,
    val startedAtEpochMillis: Long,
)

data class AdoptionTransferProgress(
    val rootNumber: Int,
    val rootCount: Int,
    val rootName: String,
    val percentage: Int,
)

enum class InitialAdoptionError {
    INVALID_CONFIGURATION,
    STORAGE_PERMISSION_REQUIRED,
    MEDIA_SNAPSHOT_UNAVAILABLE,
    PREVIEW_FAILED,
    CONFIGURATION_CHANGED,
    TRANSFER_FAILED,
    CANCELLED,
    SECURE_STORAGE_FAILED,
}

sealed interface InitialAdoptionResult<out T> {
    data class Success<T>(val value: T) : InitialAdoptionResult<T>
    data class Failure(val error: InitialAdoptionError) : InitialAdoptionResult<Nothing>
}

interface AdoptionRsyncExecutor {
    fun preview(root: InitialRootFileList, ssh: StrictSshConfig): RsyncExecutionResult
    fun transfer(
        root: InitialRootFileList,
        ssh: StrictSshConfig,
        onProgress: (RsyncProgress) -> Unit,
    ): RsyncExecutionResult

    fun cancel()
}

class NativeAdoptionRsyncExecutor(context: Context) : AdoptionRsyncExecutor {
    private val locator = NativeToolLocator(context)
    private val engine = RsyncCommandEngine()

    @Volatile
    private var running: RunningRsyncCommand? = null

    override fun preview(root: InitialRootFileList, ssh: StrictSshConfig): RsyncExecutionResult {
        val command = builder(root).adoptionPreview(root.mapping, ssh, root.file)
        return execute(command, ssh.sshHomeDirectory)
    }

    override fun transfer(
        root: InitialRootFileList,
        ssh: StrictSshConfig,
        onProgress: (RsyncProgress) -> Unit,
    ): RsyncExecutionResult {
        val command = builder(root).adoptionTransfer(root.mapping, ssh, root.file)
        return execute(command, ssh.sshHomeDirectory, onProgress)
    }

    override fun cancel() {
        running?.cancel()
    }

    private fun execute(
        command: com.d35p4c1t0.piffbackup.rsync.RsyncCommand,
        workingDirectory: File,
        onProgress: (RsyncProgress) -> Unit = {},
    ): RsyncExecutionResult {
        val process = engine.start(command, workingDirectory, onProgress)
        running = process
        return try {
            process.await()
        } finally {
            running = null
        }
    }

    private fun builder(root: InitialRootFileList) = RsyncCommandBuilder(
        rsyncExecutable = locator.require(NativeTool.RSYNC),
        sshExecutable = locator.require(NativeTool.SSH_CLIENT),
        remoteBasePath = RemoteRelativePath.create(root.entity.relativeRemotePath.substringBefore('/')),
    )
}

class InitialAdoptionCoordinator(
    private val configuration: DurableConfigurationStore,
    private val durableBackup: DurableBackupStore,
    private val mediaSource: MediaStoreSource,
    private val fileLists: InitialFileListPlanner,
    private val credentials: OnboardingCredentialManager,
    private val knownHosts: KnownHostStore,
    private val rsync: AdoptionRsyncExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var currentPreview: InitialAdoptionPreview? = null

    suspend fun preview(
        profileId: String,
        mappings: List<FolderMappingInput>,
    ): InitialAdoptionResult<InitialAdoptionPreview> {
        discardPreview()
        if (mappings.isEmpty()) return InitialAdoptionResult.Failure(InitialAdoptionError.INVALID_CONFIGURATION)
        var generatedRoots: List<InitialRootFileList> = emptyList()
        return try {
            val profileBefore = configuration.profile(profileId)
                ?: return InitialAdoptionResult.Failure(InitialAdoptionError.INVALID_CONFIGURATION)
            if (!profileBefore.setupCompleted || profileBefore.encryptedCredentialRef == null) {
                return InitialAdoptionResult.Failure(InitialAdoptionError.INVALID_CONFIGURATION)
            }
            val entities = configuration.replaceMappings(profileId, mappings)
            val profile = requireNotNull(configuration.profile(profileId))
            val snapshot = try {
                mediaSource.snapshot(VOLUME_NAME)
            } catch (exception: Exception) {
                throw AdoptionOperationException(InitialAdoptionError.MEDIA_SNAPSHOT_UNAVAILABLE, exception)
            }
            if (!snapshot.stable || snapshot.accessScope != MediaAccessScope.FULL) {
                return InitialAdoptionResult.Failure(InitialAdoptionError.STORAGE_PERMISSION_REQUIRED)
            }
            val plannedRoots = fileLists.plan(snapshot, entities)
            generatedRoots = plannedRoots
            val previewRoots = credentials.withPrivateKey(requireNotNull(profile.encryptedCredentialRef)) { key ->
                val ssh = strictConfig(profile, key)
                plannedRoots.map { root ->
                    val summary = if (root.itemCount == 0L) {
                        AdoptionPreviewSummary(0L, 0L, 0L)
                    } else {
                        val result = rsync.preview(root, ssh)
                        if (result.exitKind != RsyncExitKind.SUCCESS) {
                            throw AdoptionOperationException(InitialAdoptionError.PREVIEW_FAILED)
                        }
                        requireNotNull(result.adoptionPreviewSummary).also { previewSummary ->
                            val observed = previewSummary.alreadyBackedUpItems.checkedAdd(
                                previewSummary.itemsToUpload,
                            )
                            if (observed != root.itemCount) {
                                throw AdoptionOperationException(InitialAdoptionError.PREVIEW_FAILED)
                            }
                        }
                    }
                    AdoptionPreviewRoot(root, summary)
                }
            }
            val preview = InitialAdoptionPreview(
                id = UUID.randomUUID().toString(),
                profileId = profileId,
                configurationRevision = profile.configurationRevision,
                snapshot = snapshot,
                roots = previewRoots,
                summary = previewRoots.fold(AdoptionPreviewSummary(0L, 0L, 0L)) { total, root ->
                    AdoptionPreviewSummary(
                        alreadyBackedUpItems = total.alreadyBackedUpItems.checkedAdd(
                            root.summary.alreadyBackedUpItems,
                        ),
                        itemsToUpload = total.itemsToUpload.checkedAdd(root.summary.itemsToUpload),
                        bytesToUpload = total.bytesToUpload.checkedAdd(root.summary.bytesToUpload),
                    )
                },
                startedAtEpochMillis = clock().also { require(it >= 0L) },
            )
            currentPreview = preview
            InitialAdoptionResult.Success(preview)
        } catch (failure: AdoptionOperationException) {
            deleteGeneratedRoots(generatedRoots)
            discardPreview()
            InitialAdoptionResult.Failure(failure.error)
        } catch (_: IllegalArgumentException) {
            deleteGeneratedRoots(generatedRoots)
            discardPreview()
            InitialAdoptionResult.Failure(InitialAdoptionError.INVALID_CONFIGURATION)
        } catch (_: Exception) {
            deleteGeneratedRoots(generatedRoots)
            discardPreview()
            InitialAdoptionResult.Failure(InitialAdoptionError.SECURE_STORAGE_FAILED)
        }
    }

    suspend fun confirm(
        previewId: String,
        onProgress: (AdoptionTransferProgress) -> Unit = {},
    ): InitialAdoptionResult<AdoptionPreviewSummary> {
        val preview = currentPreview?.takeIf { it.id == previewId }
            ?: return InitialAdoptionResult.Failure(InitialAdoptionError.CONFIGURATION_CHANGED)
        return try {
            val profile = configuration.profile(preview.profileId)
                ?: throw AdoptionOperationException(InitialAdoptionError.CONFIGURATION_CHANGED)
            if (profile.configurationRevision != preview.configurationRevision) {
                throw AdoptionOperationException(InitialAdoptionError.CONFIGURATION_CHANGED)
            }
            val currentMappings = configuration.mappings(preview.profileId)
            if (!mappingsMatch(preview.roots.map { it.files.entity }, currentMappings)) {
                throw AdoptionOperationException(InitialAdoptionError.CONFIGURATION_CHANGED)
            }
            credentials.withPrivateKey(requireNotNull(profile.encryptedCredentialRef)) { key ->
                val ssh = strictConfig(profile, key)
                preview.roots.forEachIndexed { index, root ->
                    if (root.summary.itemsToUpload == 0L) return@forEachIndexed
                    val result = rsync.transfer(root.files, ssh) { progress ->
                        onProgress(
                            AdoptionTransferProgress(
                                rootNumber = index + 1,
                                rootCount = preview.roots.size,
                                rootName = root.files.entity.displayName,
                                percentage = progress.percentage,
                            ),
                        )
                    }
                    if (result.exitKind == RsyncExitKind.CANCELLED) {
                        throw AdoptionOperationException(InitialAdoptionError.CANCELLED)
                    }
                    if (result.exitKind != RsyncExitKind.SUCCESS) {
                        throw AdoptionOperationException(InitialAdoptionError.TRANSFER_FAILED)
                    }
                }
            }
            durableBackup.completeInitialAdoption(
                runId = preview.id,
                profileId = preview.profileId,
                configurationRevision = preview.configurationRevision,
                checkpoint = MediaStoreCheckpoint(
                    volumeName = preview.snapshot.volumeName,
                    version = preview.snapshot.version,
                    successfulGeneration = preview.snapshot.generation,
                ),
                startedAtEpochMillis = preview.startedAtEpochMillis,
                discoveredFiles = preview.summary.alreadyBackedUpItems.checkedAdd(
                    preview.summary.itemsToUpload,
                ),
                uploadedFiles = preview.summary.itemsToUpload,
                uploadedBytes = preview.summary.bytesToUpload,
            )
            discardPreview()
            InitialAdoptionResult.Success(preview.summary)
        } catch (failure: AdoptionOperationException) {
            InitialAdoptionResult.Failure(failure.error)
        } catch (_: Exception) {
            InitialAdoptionResult.Failure(InitialAdoptionError.SECURE_STORAGE_FAILED)
        }
    }

    fun cancel() = rsync.cancel()

    fun currentPreview(): InitialAdoptionPreview? = currentPreview

    fun discardPreview() {
        val preview = currentPreview
        currentPreview = null
        preview?.roots?.forEach { root ->
            val file = root.files.file
            if (file.isFile) file.delete()
        }
    }

    private fun deleteGeneratedRoots(roots: List<InitialRootFileList>) {
        roots.forEach { root ->
            if (root.file.isFile) root.file.delete()
        }
    }

    private fun strictConfig(profile: com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity, key: File) =
        StrictSshConfig(
            username = profile.username,
            hostname = profile.hostname,
            port = profile.port,
            identityFile = key,
            sshHomeDirectory = knownHosts.homeDirectory(profile.id),
        )

    private fun mappingsMatch(
        expected: List<FolderMappingEntity>,
        actual: List<FolderMappingEntity>,
    ): Boolean = expected.associate { it.id to mappingIdentity(it) } ==
        actual.associate { it.id to mappingIdentity(it) }

    private fun mappingIdentity(mapping: FolderMappingEntity): List<String> = listOf(
        mapping.id,
        mapping.displayName,
        mapping.treeUri,
        mapping.canonicalLocalPath,
        mapping.relativeMediaStorePrefix,
        mapping.relativeRemotePath,
        mapping.mode,
        mapping.enabled.toString(),
    )

    private fun Long.checkedAdd(value: Long): Long {
        require(value >= 0L && this <= Long.MAX_VALUE - value) { "Adoption total overflow" }
        return this + value
    }

    private class AdoptionOperationException(
        val error: InitialAdoptionError,
        cause: Throwable? = null,
    ) : Exception(error.name, cause)

    private companion object {
        const val VOLUME_NAME = "external_primary"
    }
}
