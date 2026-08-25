package com.d35p4c1t0.piffbackup.data

import androidx.room3.withWriteTransaction
import com.d35p4c1t0.piffbackup.media.MediaPlanningResult
import com.d35p4c1t0.piffbackup.media.MediaStoreCheckpoint
import java.io.File

class DurableBackupStore(
    private val database: PiffBackupDatabase,
    fileListRoot: File,
    private val clock: EpochMillisClock = SystemEpochMillisClock,
) {
    private val dao = database.dao()
    private val allowedFileListRoot = fileListRoot.canonicalFile

    init {
        require(fileListRoot.isAbsolute) { "File-list root must be absolute" }
        require(allowedFileListRoot.isDirectory || allowedFileListRoot.mkdirs()) {
            "File-list root must be available"
        }
    }

    suspend fun establishCheckpoint(
        profileId: String,
        checkpoint: MediaStoreCheckpoint,
    ): MediaCheckpointEntity = database.withWriteTransaction {
        require(dao.activeJobs(profileId).isEmpty()) { "Checkpoint cannot change while work is pending" }
        val profile = requireNotNull(dao.profile(profileId)) { "Profile does not exist" }
        val entity = MediaCheckpointEntity(
            profileId = profileId,
            volumeName = checkpoint.volumeName,
            mediaStoreVersion = checkpoint.version,
            successfulGeneration = checkpoint.successfulGeneration,
            configurationRevision = profile.configurationRevision,
            updatedAtEpochMillis = checkedNow(),
        )
        dao.upsertCheckpoint(entity)
        entity
    }

    suspend fun checkpointForPlanning(profileId: String, volumeName: String): MediaStoreCheckpoint? {
        val profile = dao.profile(profileId) ?: return null
        val checkpoint = dao.checkpoint(profileId, volumeName) ?: return null
        if (checkpoint.configurationRevision != profile.configurationRevision) return null
        return MediaStoreCheckpoint(
            volumeName = checkpoint.volumeName,
            version = checkpoint.mediaStoreVersion,
            successfulGeneration = checkpoint.successfulGeneration,
        )
    }

    suspend fun completeInitialAdoption(
        runId: String,
        profileId: String,
        configurationRevision: Long,
        checkpoint: MediaStoreCheckpoint,
        startedAtEpochMillis: Long,
        discoveredFiles: Long,
        uploadedFiles: Long,
        uploadedBytes: Long,
    ): MediaCheckpointEntity = database.withWriteTransaction {
        require(runId.isNotBlank() && '\u0000' !in runId) { "Invalid adoption run ID" }
        require(startedAtEpochMillis >= 0L) { "Invalid adoption start time" }
        require(discoveredFiles >= 0L && uploadedFiles in 0..discoveredFiles && uploadedBytes >= 0L) {
            "Invalid adoption totals"
        }
        require(dao.activeJobs(profileId).isEmpty()) { "Adoption cannot complete while work is pending" }
        require(dao.backupRun(runId) == null) { "Adoption run already exists" }
        val profile = requireNotNull(dao.profile(profileId)) { "Profile does not exist" }
        require(profile.configurationRevision == configurationRevision) {
            "Profile configuration changed during adoption"
        }
        require(dao.mappings(profileId).any { it.enabled }) { "Adoption requires an enabled mapping" }
        val now = checkedNow()
        val entity = MediaCheckpointEntity(
            profileId = profileId,
            volumeName = checkpoint.volumeName,
            mediaStoreVersion = checkpoint.version,
            successfulGeneration = checkpoint.successfulGeneration,
            configurationRevision = configurationRevision,
            updatedAtEpochMillis = now,
        )
        dao.upsertCheckpoint(entity)
        dao.insertBackupRun(
            BackupRunEntity(
                id = runId,
                profileId = profileId,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = now,
                result = BackupRunResultValue.SUCCEEDED,
                discoveredFiles = discoveredFiles,
                uploadedFiles = uploadedFiles,
                uploadedBytes = uploadedBytes,
                sanitizedErrorCode = null,
            ),
        )
        entity
    }

    suspend fun persistIncrementalPlan(
        jobId: String,
        profileId: String,
        plan: MediaPlanningResult.Incremental,
    ): DurablePendingJob {
        require(plan.hasWork) { "An empty incremental plan must not create a pending job" }
        require(plan.window.throughInclusive == plan.snapshot.generation) {
            "Plan target does not match its MediaStore snapshot"
        }
        val profile = requireNotNull(dao.profile(profileId)) { "Profile does not exist" }
        val configuredMappings = dao.mappings(profileId)
        val roots = plan.transfers.map { transfer ->
            val plannedMapping = transfer.mapping.mapping
            val matches = configuredMappings.filter { mapping ->
                mapping.enabled &&
                    mapping.mode == MappingModeValue.MEDIA_FAST &&
                    mapping.canonicalLocalPath == plannedMapping.localRoot.file.path &&
                    mapping.relativeRemotePath == plannedMapping.remoteRoot.value
            }
            require(matches.size == 1) { "A planned root no longer has one exact persisted mapping" }
            PendingRootDraft(
                folderMappingId = matches.single().id,
                fileListPath = transfer.fileList.path,
                totalFiles = transfer.itemCount,
                totalBytes = transfer.totalBytes,
            )
        }
        return persistPendingJob(
            PendingBackupJobDraft(
                id = jobId,
                profileId = profileId,
                volumeName = plan.snapshot.volumeName,
                mediaStoreVersion = plan.snapshot.version,
                configurationRevision = profile.configurationRevision,
                previousGeneration = plan.window.afterExclusive,
                targetGeneration = plan.window.throughInclusive,
                roots = roots,
            ),
        )
    }

    suspend fun persistPendingJob(draft: PendingBackupJobDraft): DurablePendingJob {
        validateJobDraft(draft)
        val canonicalLists = draft.roots.map { root -> root to requireValidFileList(root.fileListPath) }
        return database.withWriteTransaction {
            require(dao.activeJobs().isEmpty()) { "Another backup job is already pending" }
            val profile = requireNotNull(dao.profile(draft.profileId)) { "Profile does not exist" }
            require(profile.configurationRevision == draft.configurationRevision) {
                "Profile configuration changed before job persistence"
            }
            val checkpoint = requireNotNull(dao.checkpoint(draft.profileId, draft.volumeName)) {
                "Successful checkpoint does not exist"
            }
            require(
                checkpoint.mediaStoreVersion == draft.mediaStoreVersion &&
                    checkpoint.successfulGeneration == draft.previousGeneration &&
                    checkpoint.configurationRevision == draft.configurationRevision
            ) { "Checkpoint changed before job persistence" }

            val mappings = dao.mappingsById(draft.roots.map { it.folderMappingId }).associateBy { it.id }
            require(mappings.size == draft.roots.size) { "A planned mapping no longer exists" }
            val now = checkedNow()
            var totalFiles = 0L
            var totalBytes = 0L
            val roots = canonicalLists.mapIndexed { index, (rootDraft, canonicalList) ->
                val mapping = requireNotNull(mappings[rootDraft.folderMappingId])
                require(mapping.profileId == draft.profileId && mapping.enabled) {
                    "A planned mapping is not enabled for this profile"
                }
                totalFiles = totalFiles.checkedAdd(rootDraft.totalFiles, "Job file count overflow")
                totalBytes = totalBytes.checkedAdd(rootDraft.totalBytes, "Job byte count overflow")
                PendingRootWorkEntity(
                    jobId = draft.id,
                    folderMappingId = mapping.id,
                    sequence = index,
                    canonicalLocalPath = mapping.canonicalLocalPath,
                    relativeRemotePath = mapping.relativeRemotePath,
                    fileListPath = canonicalList.path,
                    status = PendingRootStatusValue.PENDING,
                    totalFiles = rootDraft.totalFiles,
                    totalBytes = rootDraft.totalBytes,
                    completedFiles = 0L,
                    completedBytes = 0L,
                    rsyncExitCode = null,
                    sanitizedErrorCode = null,
                    updatedAtEpochMillis = now,
                )
            }
            val job = PendingBackupJobEntity(
                id = draft.id,
                profileId = draft.profileId,
                volumeName = draft.volumeName,
                mediaStoreVersion = draft.mediaStoreVersion,
                configurationRevision = draft.configurationRevision,
                previousGeneration = draft.previousGeneration,
                targetGeneration = draft.targetGeneration,
                status = PendingJobStatusValue.PLANNED,
                totalFiles = totalFiles,
                totalBytes = totalBytes,
                completedFiles = 0L,
                completedBytes = 0L,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                sanitizedErrorCode = null,
            )
            dao.insertJob(job)
            dao.insertRootWork(roots)
            DurablePendingJob(job, roots)
        }
    }

    suspend fun markRootRunning(jobId: String, mappingId: String): DurablePendingJob =
        database.withWriteTransaction {
            val job = requireNotNull(dao.job(jobId)) { "Pending job does not exist" }
            require(job.status in RUNNABLE_JOB_STATUSES) { "Pending job is not runnable" }
            val root = requireNotNull(dao.rootWork(jobId, mappingId)) { "Pending root does not exist" }
            require(root.status in RUNNABLE_ROOT_STATUSES) { "Pending root is not runnable" }
            requireValidFileList(root.fileListPath)
            val now = checkedNow()
            check(
                dao.updateRootWork(
                    root.copy(
                        status = PendingRootStatusValue.RUNNING,
                        updatedAtEpochMillis = now,
                        sanitizedErrorCode = null,
                    ),
                ) == 1,
            ) { "Root update was lost" }
            val updatedJob = job.copy(
                status = PendingJobStatusValue.RUNNING,
                updatedAtEpochMillis = now,
                sanitizedErrorCode = null,
            )
            check(dao.updateJob(updatedJob) == 1) { "Job update was lost" }
            DurablePendingJob(updatedJob, dao.rootWork(jobId))
        }

    suspend fun recordRootOutcome(
        jobId: String,
        mappingId: String,
        outcome: RootExecutionOutcome,
        completedFiles: Long,
        completedBytes: Long,
        rsyncExitCode: Int?,
        sanitizedErrorCode: String? = null,
    ): DurablePendingJob = database.withWriteTransaction {
        val job = requireNotNull(dao.job(jobId)) { "Pending job does not exist" }
        if (job.status == PendingJobStatusValue.SUCCEEDED) {
            return@withWriteTransaction DurablePendingJob(job, dao.rootWork(jobId))
        }
        require(job.status in RUNNABLE_JOB_STATUSES) { "Pending job cannot accept a result" }
        val root = requireNotNull(dao.rootWork(jobId, mappingId)) { "Pending root does not exist" }
        require(root.status == PendingRootStatusValue.RUNNING) { "Root was not running" }
        require(completedFiles in 0..root.totalFiles) { "Invalid completed file count" }
        require(completedBytes in 0..root.totalBytes) { "Invalid completed byte count" }
        validateSanitizedError(sanitizedErrorCode)
        if (outcome == RootExecutionOutcome.SUCCESS) {
            require(rsyncExitCode == 0) { "Successful root must have rsync exit code zero" }
            require(sanitizedErrorCode == null) { "Successful root must not have an error code" }
        }
        val now = checkedNow()
        val updatedRoot = when (outcome) {
            RootExecutionOutcome.SUCCESS -> root.copy(
                status = PendingRootStatusValue.SUCCEEDED,
                completedFiles = root.totalFiles,
                completedBytes = root.totalBytes,
                rsyncExitCode = 0,
                sanitizedErrorCode = null,
                updatedAtEpochMillis = now,
            )

            RootExecutionOutcome.RETRYABLE_FAILURE,
            RootExecutionOutcome.CANCELLED,
            -> root.copy(
                status = PendingRootStatusValue.RETRYABLE,
                completedFiles = completedFiles,
                completedBytes = completedBytes,
                rsyncExitCode = rsyncExitCode,
                sanitizedErrorCode = sanitizedErrorCode,
                updatedAtEpochMillis = now,
            )

            RootExecutionOutcome.PERMANENT_FAILURE -> root.copy(
                status = PendingRootStatusValue.FAILED,
                completedFiles = completedFiles,
                completedBytes = completedBytes,
                rsyncExitCode = rsyncExitCode,
                sanitizedErrorCode = sanitizedErrorCode ?: DurableErrorCode.TRANSFER_FAILED,
                updatedAtEpochMillis = now,
            )
        }
        check(dao.updateRootWork(updatedRoot) == 1) { "Root result update was lost" }
        val roots = dao.rootWork(jobId)
        val aggregateFiles = roots.fold(0L) { total, item ->
            total.checkedAdd(item.completedFiles, "Completed file count overflow")
        }
        val aggregateBytes = roots.fold(0L) { total, item ->
            total.checkedAdd(item.completedBytes, "Completed byte count overflow")
        }
        val updatedJob = if (roots.all { it.status == PendingRootStatusValue.SUCCEEDED }) {
            completeJob(job, roots, now)
        } else {
            val status = when (outcome) {
                RootExecutionOutcome.CANCELLED -> PendingJobStatusValue.PAUSED
                RootExecutionOutcome.RETRYABLE_FAILURE -> PendingJobStatusValue.RETRYABLE
                RootExecutionOutcome.PERMANENT_FAILURE -> PendingJobStatusValue.FAILED
                RootExecutionOutcome.SUCCESS -> PendingJobStatusValue.RUNNING
            }
            job.copy(
                status = status,
                completedFiles = aggregateFiles,
                completedBytes = aggregateBytes,
                updatedAtEpochMillis = now,
                sanitizedErrorCode = updatedRoot.sanitizedErrorCode,
            ).also { check(dao.updateJob(it) == 1) { "Job result update was lost" } }
        }
        DurablePendingJob(updatedJob, roots)
    }

    suspend fun recoverOnLaunch(): List<DurablePendingJob> = database.withWriteTransaction {
        val recovered = mutableListOf<DurablePendingJob>()
        dao.activeJobs().forEach { job ->
            val roots = dao.rootWork(job.id)
            val errorCode = recoveryError(job, roots)
            val now = checkedNow()
            if (errorCode != null) {
                val invalidJob = job.copy(
                    status = PendingJobStatusValue.NEEDS_RECONCILIATION,
                    updatedAtEpochMillis = now,
                    sanitizedErrorCode = errorCode,
                )
                check(dao.updateJob(invalidJob) == 1) { "Recovery update was lost" }
                return@forEach
            }
            val recoveredRoots = roots.map { root ->
                if (root.status == PendingRootStatusValue.RUNNING) {
                    root.copy(
                        status = PendingRootStatusValue.RETRYABLE,
                        updatedAtEpochMillis = now,
                    ).also { check(dao.updateRootWork(it) == 1) { "Root recovery update was lost" } }
                } else {
                    root
                }
            }
            val recoveredJob = if (job.status == PendingJobStatusValue.RUNNING) {
                job.copy(
                    status = PendingJobStatusValue.RETRYABLE,
                    updatedAtEpochMillis = now,
                ).also { check(dao.updateJob(it) == 1) { "Job recovery update was lost" } }
            } else {
                job
            }
            recovered += DurablePendingJob(recoveredJob, recoveredRoots)
        }
        recovered
    }

    suspend fun cleanupSucceededJobs(): Int {
        var cleaned = 0
        dao.completedJobsAwaitingCleanup().forEach { job ->
            val roots = dao.rootWork(job.id)
            val allClean = roots.fold(true) { clean, root -> deleteExactFileList(root.fileListPath) && clean }
            if (allClean) {
                database.withWriteTransaction {
                    val current = dao.job(job.id)
                    if (current?.status == PendingJobStatusValue.SUCCEEDED && dao.deleteJob(job.id) == 1) {
                        cleaned++
                    }
                }
            }
        }
        return cleaned
    }

    suspend fun pendingJob(jobId: String): DurablePendingJob? {
        val job = dao.job(jobId) ?: return null
        return DurablePendingJob(job, dao.rootWork(jobId))
    }

    suspend fun latestSuccessfulRun(profileId: String): BackupRunEntity? =
        dao.latestSuccessfulBackupRun(profileId)

    private suspend fun completeJob(
        job: PendingBackupJobEntity,
        roots: List<PendingRootWorkEntity>,
        now: Long,
    ): PendingBackupJobEntity {
        val checkpoint = dao.checkpoint(job.profileId, job.volumeName)
        val checkpointMatches = checkpoint != null &&
            checkpoint.mediaStoreVersion == job.mediaStoreVersion &&
            checkpoint.successfulGeneration == job.previousGeneration &&
            checkpoint.configurationRevision == job.configurationRevision
        if (!checkpointMatches) {
            return job.copy(
                status = PendingJobStatusValue.NEEDS_RECONCILIATION,
                completedFiles = job.totalFiles,
                completedBytes = job.totalBytes,
                updatedAtEpochMillis = now,
                sanitizedErrorCode = DurableErrorCode.CHECKPOINT_CHANGED,
            ).also { check(dao.updateJob(it) == 1) { "Checkpoint conflict update was lost" } }
        }
        dao.upsertCheckpoint(
            requireNotNull(checkpoint).copy(
                successfulGeneration = job.targetGeneration,
                updatedAtEpochMillis = now,
            ),
        )
        val completedJob = job.copy(
            status = PendingJobStatusValue.SUCCEEDED,
            completedFiles = job.totalFiles,
            completedBytes = job.totalBytes,
            updatedAtEpochMillis = now,
            sanitizedErrorCode = null,
        )
        check(dao.updateJob(completedJob) == 1) { "Job completion update was lost" }
        dao.insertBackupRun(
            BackupRunEntity(
                id = job.id,
                profileId = job.profileId,
                startedAtEpochMillis = job.createdAtEpochMillis,
                finishedAtEpochMillis = now,
                result = BackupRunResultValue.SUCCEEDED,
                discoveredFiles = job.totalFiles,
                uploadedFiles = roots.fold(0L) { total, root ->
                    total.checkedAdd(root.totalFiles, "Uploaded file count overflow")
                },
                uploadedBytes = roots.fold(0L) { total, root ->
                    total.checkedAdd(root.totalBytes, "Uploaded byte count overflow")
                },
                sanitizedErrorCode = null,
            ),
        )
        return completedJob
    }

    private suspend fun recoveryError(
        job: PendingBackupJobEntity,
        roots: List<PendingRootWorkEntity>,
    ): String? {
        if (
            job.previousGeneration < 0L ||
            job.targetGeneration < job.previousGeneration ||
            job.totalFiles < 0L ||
            job.totalBytes < 0L ||
            job.completedFiles !in 0..job.totalFiles ||
            job.completedBytes !in 0..job.totalBytes
        ) {
            return DurableErrorCode.CORRUPT_STATE
        }
        val profile = dao.profile(job.profileId) ?: return DurableErrorCode.CONFIGURATION_CHANGED
        if (profile.configurationRevision != job.configurationRevision) {
            return DurableErrorCode.CONFIGURATION_CHANGED
        }
        val checkpoint = dao.checkpoint(job.profileId, job.volumeName)
            ?: return DurableErrorCode.CHECKPOINT_CHANGED
        if (
            checkpoint.mediaStoreVersion != job.mediaStoreVersion ||
            checkpoint.successfulGeneration != job.previousGeneration ||
            checkpoint.configurationRevision != job.configurationRevision
        ) {
            return DurableErrorCode.CHECKPOINT_CHANGED
        }
        if (
            roots.isEmpty() ||
            roots.map { it.folderMappingId }.toSet().size != roots.size ||
            roots.map { it.sequence } != roots.indices.toList() ||
            roots.any {
                it.status !in KNOWN_ROOT_STATUSES ||
                    it.totalFiles <= 0L ||
                    it.totalBytes < 0L ||
                    it.completedFiles !in 0..it.totalFiles ||
                    it.completedBytes !in 0..it.totalBytes
            }
        ) {
            return DurableErrorCode.CORRUPT_STATE
        }
        val persistedMappings = dao.mappingsById(roots.map { it.folderMappingId }).associateBy { it.id }
        if (roots.any { root ->
                val mapping = persistedMappings[root.folderMappingId]
                mapping == null ||
                    mapping.profileId != job.profileId ||
                    !mapping.enabled ||
                    mapping.canonicalLocalPath != root.canonicalLocalPath ||
                    mapping.relativeRemotePath != root.relativeRemotePath
            }
        ) {
            return DurableErrorCode.CONFIGURATION_CHANGED
        }
        val totals = runCatching {
            roots.fold(0L to 0L) { total, root ->
                total.first.checkedAdd(root.totalFiles, "overflow") to
                    total.second.checkedAdd(root.totalBytes, "overflow")
            }
        }.getOrNull() ?: return DurableErrorCode.CORRUPT_STATE
        val (totalFiles, totalBytes) = totals
        if (totalFiles != job.totalFiles || totalBytes != job.totalBytes) {
            return DurableErrorCode.CORRUPT_STATE
        }
        if (roots.filter { it.status != PendingRootStatusValue.SUCCEEDED }.any { !isValidFileList(it.fileListPath) }) {
            return DurableErrorCode.FILE_LIST_MISSING
        }
        return null
    }

    private fun validateJobDraft(draft: PendingBackupJobDraft) {
        require(SAFE_ID.matches(draft.id) && SAFE_ID.matches(draft.profileId)) { "Invalid job or profile ID" }
        require(draft.volumeName.isNotBlank() && '\u0000' !in draft.volumeName) { "Invalid volume name" }
        require(draft.mediaStoreVersion.isNotBlank() && '\u0000' !in draft.mediaStoreVersion) {
            "Invalid MediaStore version"
        }
        require(draft.configurationRevision >= 0L) { "Invalid configuration revision" }
        require(draft.previousGeneration >= 0L && draft.targetGeneration >= draft.previousGeneration) {
            "Invalid generation window"
        }
        require(draft.roots.isNotEmpty()) { "A pending job must contain root work" }
        require(draft.roots.map { it.folderMappingId }.toSet().size == draft.roots.size) {
            "A pending job cannot contain duplicate mappings"
        }
        require(draft.roots.map { File(it.fileListPath).absolutePath }.toSet().size == draft.roots.size) {
            "A pending job cannot reuse a file list"
        }
        draft.roots.forEach { root ->
            require(SAFE_ID.matches(root.folderMappingId)) { "Invalid mapping ID" }
            require(root.totalFiles > 0L) { "Root work must contain files" }
            require(root.totalBytes >= 0L) { "Root byte count must not be negative" }
        }
    }

    private fun requireValidFileList(rawPath: String): File {
        val file = File(rawPath)
        require(file.isAbsolute && '\u0000' !in rawPath) { "Invalid file-list path" }
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(allowedFileListRoot.toPath())) {
            "File list escaped app-private storage"
        }
        require(canonical.isFile && canonical.canRead() && canonical.length() > 0L) {
            "File list is missing or empty"
        }
        return canonical
    }

    private fun isValidFileList(rawPath: String): Boolean =
        runCatching { requireValidFileList(rawPath) }.isSuccess

    private fun deleteExactFileList(rawPath: String): Boolean {
        val file = runCatching {
            val candidate = File(rawPath)
            require(candidate.isAbsolute && '\u0000' !in rawPath)
            candidate.canonicalFile.also {
                require(it.toPath().startsWith(allowedFileListRoot.toPath()))
            }
        }.getOrNull() ?: return false
        return !file.exists() || file.delete()
    }

    private fun validateSanitizedError(value: String?) {
        require(value == null || SANITIZED_ERROR.matches(value)) { "Error code must be sanitized" }
    }

    private fun checkedNow(): Long = clock.now().also { require(it >= 0L) { "Clock must not be negative" } }

    private fun Long.checkedAdd(value: Long, message: String): Long {
        require(value >= 0L && this <= Long.MAX_VALUE - value) { message }
        return this + value
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SANITIZED_ERROR = Regex("[A-Z][A-Z0-9_]{0,63}")
        val RUNNABLE_JOB_STATUSES = setOf(
            PendingJobStatusValue.PLANNED,
            PendingJobStatusValue.RUNNING,
            PendingJobStatusValue.PAUSED,
            PendingJobStatusValue.RETRYABLE,
        )
        val RUNNABLE_ROOT_STATUSES = setOf(
            PendingRootStatusValue.PENDING,
            PendingRootStatusValue.RETRYABLE,
        )
        val KNOWN_ROOT_STATUSES = RUNNABLE_ROOT_STATUSES + setOf(
            PendingRootStatusValue.RUNNING,
            PendingRootStatusValue.SUCCEEDED,
            PendingRootStatusValue.FAILED,
        )
    }
}
