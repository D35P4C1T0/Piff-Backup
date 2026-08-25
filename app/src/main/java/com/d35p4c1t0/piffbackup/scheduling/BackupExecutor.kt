package com.d35p4c1t0.piffbackup.scheduling

import android.content.Context
import android.os.Environment
import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.PendingRootStatusValue
import com.d35p4c1t0.piffbackup.data.PendingRootWorkEntity
import com.d35p4c1t0.piffbackup.data.RootExecutionOutcome
import com.d35p4c1t0.piffbackup.onboarding.KnownHostStore
import com.d35p4c1t0.piffbackup.onboarding.OnboardingCredentialManager
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.rsync.RsyncCommandBuilder
import com.d35p4c1t0.piffbackup.rsync.RsyncCommandEngine
import com.d35p4c1t0.piffbackup.rsync.RsyncExitKind
import com.d35p4c1t0.piffbackup.rsync.RunningRsyncCommand
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class BackupExecutionResult {
    SUCCEEDED,
    RETRY,
    FAILED,
    PAUSED,
}

fun interface BackupExecutionReporter {
    fun report(event: BackupProgressEvent)
}

class BackupExecutor(
    context: Context,
    private val configuration: DurableConfigurationStore,
    private val durableBackup: DurableBackupStore,
    private val credentials: OnboardingCredentialManager,
    private val knownHosts: KnownHostStore,
    private val volumeRoot: File = Environment.getExternalStorageDirectory(),
) {
    private val locator = NativeToolLocator(context)
    private val engine = RsyncCommandEngine()
    private val executionLock = ReentrantLock()
    private val running = AtomicReference<RunningRsyncCommand?>(null)
    private val cancelledJob = AtomicReference<String?>(null)
    private val explicitlyPausedJob = AtomicReference<String?>(null)

    suspend fun execute(
        jobId: String,
        reporter: BackupExecutionReporter = BackupExecutionReporter {},
    ): BackupExecutionResult {
        if (!wasExplicitlyPaused(jobId)) cancelledJob.compareAndSet(jobId, null)
        return withContext(Dispatchers.IO) {
            executionLock.withLock { executeLocked(jobId, reporter) }
        }
    }

    fun requestStop(jobId: String, explicitPause: Boolean) {
        cancelledJob.set(jobId)
        if (explicitPause) explicitlyPausedJob.set(jobId)
        running.get()?.cancel()
    }

    fun clearStop(jobId: String) {
        cancelledJob.compareAndSet(jobId, null)
        explicitlyPausedJob.compareAndSet(jobId, null)
    }

    fun wasExplicitlyPaused(jobId: String): Boolean = explicitlyPausedJob.get() == jobId

    private fun executeLocked(jobId: String, reporter: BackupExecutionReporter): BackupExecutionResult {
        if (cancelledJob.get() == jobId) return BackupExecutionResult.PAUSED
        val initial = runBlocking { durableBackup.pendingJob(jobId) } ?: return BackupExecutionResult.SUCCEEDED
        val profile = runBlocking { configuration.profile(initial.job.profileId) }
            ?: return BackupExecutionResult.FAILED
        val reconciliation = BackupJobKind.isReconciliation(initial.job.id)
        val credentialReference = profile.encryptedCredentialRef ?: return BackupExecutionResult.FAILED
        return try {
            credentials.withPrivateKey(credentialReference) { key ->
                val ssh = StrictSshConfig(
                    username = profile.username,
                    hostname = profile.hostname,
                    port = profile.port,
                    identityFile = key,
                    sshHomeDirectory = knownHosts.homeDirectory(profile.id),
                )
                initial.roots.filter { it.status != PendingRootStatusValue.SUCCEEDED }.forEach { root ->
                    if (cancelledJob.get() == jobId) {
                        runBlocking { durableBackup.markJobPaused(jobId) }
                        publish(jobId, BackupProgressStatus.PAUSED, percentage(initial.job), reporter)
                        return@withPrivateKey BackupExecutionResult.PAUSED
                    }
                    val runningJob = runBlocking { durableBackup.markRootRunning(jobId, root.folderMappingId) }
                    val command = command(profile.remoteBasePath, root, ssh, reconciliation)
                    var transferredBytes = root.completedBytes
                    val process = engine.start(command, ssh.sshHomeDirectory) { progress ->
                        transferredBytes = progress.transferredBytes.coerceIn(0L, root.totalBytes)
                        val percentage = aggregatePercentage(
                            totalBytes = runningJob.job.totalBytes,
                            completedRootBytes = runningJob.roots
                                .filter { it.status == PendingRootStatusValue.SUCCEEDED }
                                .sumOf { it.totalBytes },
                            currentRootBytes = transferredBytes,
                            fallback = progress.percentage,
                        )
                        publish(jobId, BackupProgressStatus.RUNNING, percentage, reporter)
                    }
                    running.set(process)
                    val result = try {
                        process.await()
                    } finally {
                        running.compareAndSet(process, null)
                    }
                    val outcome = when {
                        result.exitKind == RsyncExitKind.SUCCESS -> RootExecutionOutcome.SUCCESS
                        result.exitKind == RsyncExitKind.CANCELLED || cancelledJob.get() == jobId -> {
                            RootExecutionOutcome.CANCELLED
                        }
                        result.exitKind.retryable -> RootExecutionOutcome.RETRYABLE_FAILURE
                        else -> RootExecutionOutcome.PERMANENT_FAILURE
                    }
                    val updated = runBlocking {
                        durableBackup.recordRootOutcome(
                            jobId = jobId,
                            mappingId = root.folderMappingId,
                            outcome = outcome,
                            completedFiles = if (outcome == RootExecutionOutcome.SUCCESS) {
                                root.totalFiles
                            } else {
                                root.completedFiles
                            },
                            completedBytes = if (outcome == RootExecutionOutcome.SUCCESS) {
                                root.totalBytes
                            } else {
                                transferredBytes
                            },
                            rsyncExitCode = result.process.exitCode,
                            sanitizedErrorCode = if (outcome == RootExecutionOutcome.SUCCESS) {
                                null
                            } else {
                                "RSYNC_${result.exitKind.name}"
                            },
                        )
                    }
                    when (outcome) {
                        RootExecutionOutcome.SUCCESS -> Unit
                        RootExecutionOutcome.CANCELLED -> {
                            publish(jobId, BackupProgressStatus.PAUSED, percentage(updated.job), reporter)
                            return@withPrivateKey BackupExecutionResult.PAUSED
                        }
                        RootExecutionOutcome.RETRYABLE_FAILURE -> {
                            publish(jobId, BackupProgressStatus.FAILED, percentage(updated.job), reporter)
                            return@withPrivateKey BackupExecutionResult.RETRY
                        }
                        RootExecutionOutcome.PERMANENT_FAILURE -> {
                            publish(jobId, BackupProgressStatus.FAILED, percentage(updated.job), reporter)
                            return@withPrivateKey BackupExecutionResult.FAILED
                        }
                    }
                }
                publish(jobId, BackupProgressStatus.SUCCEEDED, 100, reporter)
                runCatching { runBlocking { durableBackup.cleanupSucceededJobs() } }
                BackupExecutionResult.SUCCEEDED
            }
        } catch (_: Exception) {
            val pending = runBlocking { durableBackup.pendingJob(jobId) }
            val activeRoot = pending?.roots?.firstOrNull { it.status == PendingRootStatusValue.RUNNING }
            if (activeRoot != null) {
                runBlocking {
                    durableBackup.recordRootOutcome(
                        jobId = jobId,
                        mappingId = activeRoot.folderMappingId,
                        outcome = if (cancelledJob.get() == jobId) {
                            RootExecutionOutcome.CANCELLED
                        } else {
                            RootExecutionOutcome.RETRYABLE_FAILURE
                        },
                        completedFiles = activeRoot.completedFiles,
                        completedBytes = activeRoot.completedBytes,
                        rsyncExitCode = null,
                        sanitizedErrorCode = "EXECUTION_FAILED",
                    )
                }
            }
            val paused = cancelledJob.get() == jobId
            publish(
                jobId,
                if (paused) BackupProgressStatus.PAUSED else BackupProgressStatus.FAILED,
                pending?.job?.let(::percentage) ?: 0,
                reporter,
            )
            if (paused) BackupExecutionResult.PAUSED else BackupExecutionResult.RETRY
        }
    }

    private fun command(
        remoteBasePath: String,
        root: PendingRootWorkEntity,
        ssh: StrictSshConfig,
        reconciliation: Boolean,
    ) = RsyncCommandBuilder(
        rsyncExecutable = locator.require(NativeTool.RSYNC),
        sshExecutable = locator.require(NativeTool.SSH_CLIENT),
        remoteBasePath = RemoteRelativePath.create(remoteBasePath),
    ).let { builder ->
        val mapping = BackupMapping(
            localRoot = CanonicalLocalRoot.create(root.canonicalLocalPath, volumeRoot),
            remoteRoot = RemoteRelativePath.create(root.relativeRemotePath),
        )
        if (reconciliation) {
            builder.adoptionTransfer(mapping, ssh, File(root.fileListPath))
        } else {
            builder.incrementalTransfer(mapping, File(root.fileListPath), ssh)
        }
    }

    private fun publish(
        jobId: String,
        status: BackupProgressStatus,
        percentage: Int,
        reporter: BackupExecutionReporter,
    ) {
        val event = BackupProgressEvent(jobId, status, percentage.coerceIn(0, 100))
        BackupProgressEvents.publish(event)
        reporter.report(event)
    }

    private fun percentage(job: com.d35p4c1t0.piffbackup.data.PendingBackupJobEntity): Int =
        aggregatePercentage(job.totalBytes, job.completedBytes, 0L, 0)

    private fun aggregatePercentage(
        totalBytes: Long,
        completedRootBytes: Long,
        currentRootBytes: Long,
        fallback: Int,
    ): Int = if (totalBytes > 0L) {
        (((completedRootBytes + currentRootBytes).coerceAtMost(totalBytes) * 100L) / totalBytes).toInt()
    } else {
        fallback.coerceIn(0, 100)
    }
}
