package com.d35p4c1t0.piffbackup.data

import androidx.room3.withWriteTransaction
import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.BackupMappingValidator
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import java.io.File
import kotlinx.coroutines.sync.withLock

class DurableConfigurationStore(
    private val database: PiffBackupDatabase,
    allowedSharedStorageRoot: File,
    private val clock: EpochMillisClock = SystemEpochMillisClock,
    private val allowedRoots: List<File> = listOf(allowedSharedStorageRoot),
    private val selectionMutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
    private val activateSelection: (BackupSelectionEntity) -> Unit = {},
) {
    private val sharedStorageRoot = allowedSharedStorageRoot.canonicalFile
    private val dao = database.dao()

    suspend fun saveProfile(input: StorageBoxProfileInput): StorageBoxProfileEntity =
        database.withWriteTransaction {
            validateProfile(input)
            val existing = dao.profile(input.id)
            if (existing != null) {
                require(dao.activeJobs(input.id).isEmpty() && dao.activeOperations().isEmpty()) {
                    "Profile cannot change while backup work is pending"
                }
            }
            val now = clock.now().also { require(it >= 0L) { "Clock must not be negative" } }
            val entity = StorageBoxProfileEntity(
                id = input.id,
                username = input.username,
                hostname = input.hostname,
                port = input.port,
                remoteBasePath = RemoteRelativePath.create(input.remoteBasePath).value,
                encryptedCredentialRef = input.encryptedCredentialRef,
                pinnedHostKey = input.pinnedHostKey,
                setupCompleted = input.setupCompleted,
                provider = input.provider,
                configurationRevision = existing?.configurationRevision?.checkedIncrement() ?: 0L,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            )
            if (existing == null) {
                dao.insertProfile(entity)
            } else {
                check(dao.updateProfile(entity) == 1) { "Profile update was lost" }
            }
            entity
        }

    suspend fun replaceMappings(
        profileId: String,
        inputs: List<FolderMappingInput>,
    ): List<FolderMappingEntity> = database.withWriteTransaction {
        require(dao.activeJobs(profileId).isEmpty() && dao.activeOperations().isEmpty()) {
            "Mappings cannot change while backup work is pending"
        }
        val profile = requireNotNull(dao.profile(profileId)) { "Profile does not exist" }
        require(inputs.map { it.id }.toSet().size == inputs.size) { "Mapping IDs must be unique" }
        val validatedMappings = inputs.map { input ->
            validateMappingInput(input)
            BackupMapping(
                localRoot = CanonicalLocalRoot.create(input.canonicalLocalPath, allowedRoots),
                remoteRoot = RemoteRelativePath.create(input.relativeRemotePath),
            )
        }
        BackupMappingValidator.validate(
            validatedMappings,
            RemoteRelativePath.create(profile.remoteBasePath),
        )
        val existingById = dao.mappings(profileId).associateBy { it.id }
        val now = clock.now().also { require(it >= 0L) { "Clock must not be negative" } }
        val entities = inputs.mapIndexed { index, input ->
            val mapping = validatedMappings[index]
            FolderMappingEntity(
                id = input.id,
                profileId = profileId,
                displayName = input.displayName,
                treeUri = input.treeUri,
                canonicalLocalPath = mapping.localRoot.file.path,
                relativeMediaStorePrefix = normalizeMediaPrefix(input.relativeMediaStorePrefix),
                relativeRemotePath = mapping.remoteRoot.value,
                mode = input.mode,
                enabled = input.enabled,
                createdAtEpochMillis = existingById[input.id]?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            )
        }
        if (entities.isEmpty()) {
            dao.deleteMappings(profileId)
        } else {
            dao.upsertMappings(entities)
            dao.deleteMappingsExcept(profileId, entities.map { it.id })
        }
        entities.forEach { dao.deleteFolderHealth(it.id); dao.deleteLocalMetadata(it.id) }
        dao.saveHealth(BackupHealthEntity(profileId,null,null,null))
        val revisedProfile = profile.copy(
            configurationRevision = profile.configurationRevision.checkedIncrement(),
            updatedAtEpochMillis = now,
        )
        check(dao.updateProfile(revisedProfile) == 1) { "Profile revision update was lost" }
        entities
    }

    suspend fun invalidateDiscovery(profileId: String) {
        database.withWriteTransaction {
            require(dao.activeJobs(profileId).isEmpty() && dao.activeOperations().isEmpty()) { "Pause and discard pending work before changing coverage" }
            val profile = requireNotNull(dao.profile(profileId))
            dao.updateProfile(profile.copy(configurationRevision = profile.configurationRevision.checkedIncrement(),
                updatedAtEpochMillis = clock.now()))
            dao.mappings(profileId).forEach { dao.deleteLocalMetadata(it.id); dao.deleteFolderHealth(it.id) }
            dao.saveHealth(BackupHealthEntity(profileId,null,null,null))
        }
    }

    suspend fun updateSelectionPolicy(policy: BackupSelectionEntity) = selectionMutex.withLock {
        policy.validate()
        database.withWriteTransaction {
            invalidateDiscovery(policy.profileId)
            dao.saveSelectionPolicy(policy)
        }
        activateSelection(policy)
    }

    suspend fun importConfiguration(profile: StorageBoxProfileInput, mappings: List<FolderMappingInput>, policy: BackupSelectionEntity? = null) = selectionMutex.withLock {
        policy?.validate()
        database.withWriteTransaction {
            require(dao.activeJobs().isEmpty() && dao.activeOperations().isEmpty())
            saveProfile(profile.copy(encryptedCredentialRef = null, setupCompleted = false))
            dao.deleteMappings(profile.id)
            replaceMappings(profile.id, mappings)
            policy?.let { dao.saveSelectionPolicy(it.copy(profileId = profile.id)) }
        }
        policy?.let(activateSelection)
    }

    suspend fun hasActiveOperation() = dao.activeOperations().isNotEmpty()

    suspend fun profile(profileId: String): StorageBoxProfileEntity? = dao.profile(profileId)

    suspend fun mappings(profileId: String): List<FolderMappingEntity> = dao.mappings(profileId)

    private fun validateProfile(input: StorageBoxProfileInput) {
        require(safeId.matches(input.id)) { "Invalid profile ID" }
        require(username.matches(input.username)) { "Invalid username" }
        require(hostname.matches(input.hostname)) { "Invalid hostname" }
        require(input.port in 1..65535) { "Invalid port" }
        com.d35p4c1t0.piffbackup.transport.RsyncTargetProvider.valueOf(input.provider)
        require(input.encryptedCredentialRef?.let { it.isNotBlank() && '\u0000' !in it } != false) {
            "Invalid encrypted credential reference"
        }
        require(input.pinnedHostKey?.let { it.isNotBlank() && '\u0000' !in it } != false) {
            "Invalid host-key pin"
        }
    }

    private fun validateMappingInput(input: FolderMappingInput) {
        require(safeId.matches(input.id)) { "Invalid mapping ID" }
        require(input.displayName.isNotBlank() && '\u0000' !in input.displayName) { "Invalid display name" }
        require(input.treeUri.startsWith(EXTERNAL_STORAGE_TREE_PREFIX) && '\u0000' !in input.treeUri) {
            "Only local ExternalStorageProvider tree tokens are accepted"
        }
        require(input.mode in MappingModeValue.ALL) { "Invalid mapping mode" }
        if (input.mode == MappingModeValue.MEDIA_FAST) {
            require(File(input.canonicalLocalPath).canonicalFile.toPath().startsWith(sharedStorageRoot.toPath())) {
                "Removable storage requires All files mode"
            }
        }
        normalizeMediaPrefix(input.relativeMediaStorePrefix)
    }

    private fun normalizeMediaPrefix(value: String): String {
        require(!value.startsWith('/') && '\u0000' !in value) { "Invalid MediaStore prefix" }
        val normalized = value.trimEnd('/')
        if (normalized.isEmpty()) return ""
        require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid MediaStore prefix"
        }
        return "$normalized/"
    }

    private fun Long.checkedIncrement(): Long {
        require(this < Long.MAX_VALUE) { "Configuration revision overflow" }
        return this + 1L
    }

    private companion object {
        val safeId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val username = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val hostname = Regex("(?=.{1,253}\\z)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
        const val EXTERNAL_STORAGE_TREE_PREFIX =
            "content://com.android.externalstorage.documents/tree/"
    }
}
