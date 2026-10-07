package com.d35p4c1t0.piffbackup.operations

import androidx.room3.withWriteTransaction
import com.d35p4c1t0.piffbackup.PiffBackupApp
import com.d35p4c1t0.piffbackup.adoption.InitialFileListPlanner
import com.d35p4c1t0.piffbackup.backup.*
import com.d35p4c1t0.piffbackup.data.*
import com.d35p4c1t0.piffbackup.media.*
import com.d35p4c1t0.piffbackup.onboarding.HostKeyPin
import com.d35p4c1t0.piffbackup.rsync.*
import com.d35p4c1t0.piffbackup.scheduling.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Restore owns a newly created directory; verification never mutates either endpoint. */
class RemoteOperationExecutor(private val app: PiffBackupApp) {
    private val dao get() = app.database.dao()
    private val running = AtomicReference<RunningRsyncCommand?>(null)
    private val stopped = AtomicReference<String?>(null)
    private val lock = ReentrantLock()
    private val stopper = java.util.concurrent.Executors.newSingleThreadExecutor()

    suspend fun prepareRestore(remotePath: String, parent: File, file: Boolean = false, archive: Boolean = false): RemoteOperationEntity {
        app.awaitReady()
        val profile = requireNotNull(app.configurationStore.profile("primary"))
        val path = RemoteRelativePath.create(remotePath)
        require(RemoteRelativePath.create(profile.remoteBasePath).isSameOrAncestorOf(path))
        require(parent.isDirectory && parent.canWrite())
        val destination = File(parent.canonicalFile, "PiffBackup-restored-${UUID.randomUUID()}")
        require(destination.mkdir()) { "Restore destination cannot be created" }
        try { return prepare(if (archive) "ARCHIVE_RESTORE" else if (file) "RESTORE_FILE" else "RESTORE", path.value, destination.path, null) }
        catch (failure: Exception) { destination.delete(); throw failure }
    }

    suspend fun prepareArchive(): RemoteOperationEntity {
        require(app.encryptedArchives.hasKey()) { "Export a recovery key first" }
        val profile = requireNotNull(app.configurationStore.profile("primary"))
        return prepare("ARCHIVE_BACKUP", "${profile.remoteBasePath}/Encrypted", null, null)
    }

    suspend fun prepareSafArchive(uri: String): RemoteOperationEntity {
        require(app.encryptedArchives.hasKey()) { "Export a recovery key first" }
        require(android.provider.DocumentsContract.isTreeUri(android.net.Uri.parse(uri)))
        val profile = requireNotNull(app.configurationStore.profile("primary"))
        return prepare("ARCHIVE_SAF_BACKUP", "${profile.remoteBasePath}/Encrypted", uri, null)
    }

    suspend fun prepareVerification(mapping: FolderMappingEntity, sampled: Boolean) =
        prepare(if (sampled) "VERIFY_SAMPLE" else "VERIFY_FULL", mapping.relativeRemotePath, null, mapping.id)

    private suspend fun prepare(kind: String, path: String, local: String?, mapping: String?): RemoteOperationEntity {
        return app.database.withWriteTransaction {
            require(dao.activeJobs().isEmpty() && dao.activeOperations().isEmpty()) { "Finish or discard pending work first" }
            RemoteOperationEntity("operation-${UUID.randomUUID()}", "primary", mapping, kind, path, local,
                "PLANNED", System.currentTimeMillis(), null, 0, 0, null).also { dao.saveOperation(it) }
        }
    }

    fun clearStop(id: String) { stopped.compareAndSet(id, null) }
    fun stop(id: String) {
        stopped.set(id)
        running.get()?.let { process -> stopper.execute { process.cancel() } }
    }
    suspend fun pause(id: String) {
        stop(id)
        withContext(Dispatchers.IO) { lock.withLock { Unit } }
        dao.operation(id)?.takeIf { it.status in setOf("PLANNED", "QUEUED", "RUNNING", "PAUSED") }?.let {
            dao.saveOperation(it.copy(status = "PAUSED"))
        }
    }
    suspend fun discard(id: String) {
        pause(id)
        dao.operation(id)?.let { dao.saveOperation(it.copy(status = "SUPERSEDED", finishedAtEpochMillis = System.currentTimeMillis())) }
        app.encryptedArchives.staged(id).delete()
    }

    suspend fun execute(id: String, reporter: BackupExecutionReporter): BackupExecutionResult = try {
        app.awaitReady()
        withContext(Dispatchers.IO) { runInterruptible {
            lock.lockInterruptibly()
            try { runBlocking { executeLocked(id, reporter) } } finally { lock.unlock() }
        } }
    } catch (cancelled: CancellationException) {
        stop(id)
        withContext(NonCancellable + Dispatchers.IO) {
            dao.operation(id)?.takeIf { it.status == "RUNNING" }?.let { dao.saveOperation(it.copy(status = "PAUSED")) }
        }
        throw cancelled
    }

    private suspend fun executeLocked(id: String, reporter: BackupExecutionReporter): BackupExecutionResult {
        val operation = dao.operation(id) ?: return BackupExecutionResult.FAILED
        if (operation.status == "SUCCEEDED") return BackupExecutionResult.SUCCEEDED
        if (operation.status !in setOf("PLANNED", "QUEUED", "RUNNING", "PAUSED")) return BackupExecutionResult.FAILED
        if (stopped.get() == id) return BackupExecutionResult.PAUSED
        dao.saveOperation(operation.copy(status = "RUNNING"))
        var artifacts: File? = null
        return try {
            val profile = requireNotNull(app.configurationStore.profile(operation.profileId))
            val locator = NativeToolLocator(app)
            val home = app.knownHostStore.write(profile.id, profile.hostname, HostKeyPin.parse(requireNotNull(profile.pinnedHostKey)))
            if (operation.kind == "ARCHIVE_BACKUP") app.encryptedArchives.create(id)
            if (operation.kind == "ARCHIVE_SAF_BACKUP") app.encryptedArchives.createSaf(id, android.net.Uri.parse(requireNotNull(operation.localDestination)))
            var compared = 0L
            var changed = 0L
            var stable = true
            val result = app.onboardingCredentials.withPrivateKey(requireNotNull(profile.encryptedCredentialRef)) { key ->
                val ssh = StrictSshConfig(profile.username, profile.hostname, profile.port, key, home)
                val command = if (operation.kind in setOf("RESTORE", "RESTORE_FILE")) {
                    restoreCommand(locator.require(NativeTool.RSYNC), locator.require(NativeTool.SSH_CLIENT), ssh,
                        RemoteRelativePath.create(operation.remotePath), File(requireNotNull(operation.localDestination)),
                        RemoteRelativePath.create(profile.remoteBasePath), operation.kind == "RESTORE_FILE")
                } else if (operation.kind.startsWith("ARCHIVE")) {
                    archiveCommand(locator.require(NativeTool.RSYNC), locator.require(NativeTool.SSH_CLIENT), ssh,
                        operation, app.encryptedArchives.staged(id))
                } else {
                    val mapping = runBlocking { app.configurationStore.mappings(profile.id) }
                        .single { it.id == operation.mappingId && it.enabled }
                    val media = AndroidMediaStoreSource(app)
                    val planner = InitialFileListPlanner(media, app.adoptionFileLists,
                        android.os.Environment.getExternalStorageDirectory(), app.allowedStorageRoots, app.backupPreferences::selection)
                    val root = planner.plan(media.snapshot("external_primary"), listOf(mapping)).single()
                    artifacts = root.file
                    if (operation.kind == "VERIFY_SAMPLE") FileListSampling.sample(root.file, id)
                    compared = countItems(root.file)
                    if (compared == 0L) {
                        // An empty mapping verifies only that there are no selected local files.
                        stable = true
                        null
                    } else {
                        app.allFilesMetadataPlanner.writeSnapshotForFileList(mapping, root.file.path)
                        RsyncCommandBuilder(locator.require(NativeTool.RSYNC), locator.require(NativeTool.SSH_CLIENT),
                            RemoteRelativePath.create(profile.remoteBasePath))
                            .adoptionPreview(root.mapping, ssh, root.file, RsyncComparisonPolicy.CONTENT)
                    }
                }
                if (command == null) null else {
                    val process = RsyncCommandEngine().start(command, home, onProgress = { progress ->
                        publish(id, BackupProgressStatus.RUNNING, progress.percentage, reporter)
                    })
                    running.set(process)
                    if (stopped.get() == id) process.cancel()
                    try { process.await() } finally { running.compareAndSet(process, null) }
                }
            }
            if (operation.kind in setOf("RESTORE", "RESTORE_FILE") && result?.exitKind == RsyncExitKind.SUCCESS && stopped.get() != id) {
                val keyReference = requireNotNull(profile.encryptedCredentialRef)
                val verified = app.onboardingCredentials.withPrivateKey(keyReference) { key ->
                    val ssh = StrictSshConfig(profile.username, profile.hostname, profile.port, key, home)
                    val transfer = restoreCommand(locator.require(NativeTool.RSYNC), locator.require(NativeTool.SSH_CLIENT), ssh,
                        RemoteRelativePath.create(operation.remotePath), File(requireNotNull(operation.localDestination)),
                        RemoteRelativePath.create(profile.remoteBasePath), operation.kind == "RESTORE_FILE")
                    val verify = transfer.copy(arguments = transfer.arguments.filter { it != "--ignore-existing" }.let {
                        it.take(1) + listOf("--dry-run", "--itemize-changes",
                            "--out-format=${RsyncCommandBuilder.ITEM_RECORD_FORMAT}") + it.drop(1)
                    }, outputKind = RsyncOutputKind.ADOPTION_PREVIEW)
                    val process = RsyncCommandEngine().start(verify,home)
                    running.set(process); if (stopped.get() == id) process.cancel()
                    try { process.await() } finally { running.compareAndSet(process,null) }
                }
                stable = verified.exitKind == RsyncExitKind.SUCCESS && verified.adoptionPreviewSummary?.itemsToUpload == 0L
                changed = verified.adoptionPreviewSummary?.itemsToUpload ?: 0L
            }
            if (operation.kind == "ARCHIVE_RESTORE" && result?.exitKind == RsyncExitKind.SUCCESS && stopped.get() != id) {
                app.encryptedArchives.restore(id, File(requireNotNull(operation.localDestination)))
            }
            if (operation.kind.startsWith("VERIFY")) changed = result?.adoptionPreviewSummary?.itemsToUpload ?: 0L
            if (operation.kind.startsWith("VERIFY") && artifacts != null) {
                val mapping = app.configurationStore.mappings(profile.id).single { it.id == operation.mappingId }
                if (compared > 0) stable = app.allFilesMetadataPlanner.matchesSnapshot(mapping, artifacts!!.path)
            }
            val paused = result?.exitKind == RsyncExitKind.CANCELLED || stopped.get() == id
            val success = !paused && (result == null || result.exitKind == RsyncExitKind.SUCCESS) && changed == 0L && stable
            val status = if (paused) "PAUSED" else if (success) "SUCCEEDED" else "FAILED"
            val error = if (paused || success) null else if (!stable) "SOURCE_CHANGED"
                else if (changed > 0L) "REMOTE_DIFFERENCES" else "REMOTE_OPERATION_FAILED"
            dao.saveOperation(operation.copy(status = status, finishedAtEpochMillis = if (paused) null else System.currentTimeMillis(),
                comparedFiles = compared, changedFiles = changed, errorCode = error))
            if (success && operation.kind.startsWith("ARCHIVE")) app.encryptedArchives.staged(id).delete()
            if (success && operation.kind.startsWith("VERIFY")) app.durableBackupStore.recordVerification(profile.id, operation.kind, operation.mappingId)
            dao.pruneOperations(profile.id)
            publish(id, if (paused) BackupProgressStatus.PAUSED else if (success) BackupProgressStatus.SUCCEEDED
                else BackupProgressStatus.FAILED, if (success) 100 else 0, reporter)
            if (paused) BackupExecutionResult.PAUSED else if (success) BackupExecutionResult.SUCCEEDED else BackupExecutionResult.FAILED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { throw interrupted }
        catch (failure: Exception) {
            val error = when (failure) {
                is javax.crypto.AEADBadTagException -> "ARCHIVE_AUTHENTICATION_FAILED"
                is SecurityException -> "STORAGE_PERMISSION_REQUIRED"
                is java.io.FileNotFoundException -> "SOURCE_UNAVAILABLE"
                else -> "REMOTE_OPERATION_FAILED"
            }
            dao.saveOperation(operation.copy(status = "FAILED", finishedAtEpochMillis = System.currentTimeMillis(), errorCode = error))
            publish(id, BackupProgressStatus.FAILED, 0, reporter)
            BackupExecutionResult.FAILED
        } finally {
            artifacts?.let { file -> app.durableBackupStore.deleteLocalMetadataSnapshot(file.path); file.delete() }
        }
    }

    private fun publish(id: String, status: BackupProgressStatus, percentage: Int, reporter: BackupExecutionReporter) {
        val event = BackupProgressEvent(id, status, percentage)
        BackupProgressEvents.publish(event); reporter.report(event)
    }

    private fun countItems(file: File): Long = file.inputStream().use { input ->
        var count = 0L; val bytes = ByteArray(8192)
        while (true) { val read = input.read(bytes); if (read < 0) break
            for (index in 0 until read) if (bytes[index] == 0.toByte()) count++ }
        count
    }

    companion object {
        fun archiveCommand(rsync: File, client: File, ssh: StrictSshConfig,
            operation: RemoteOperationEntity, staged: File): RsyncCommand {
            val upload = operation.kind.endsWith("BACKUP")
            val remote = "${ssh.username}@${ssh.hostname}:" + if (upload)
                "${operation.remotePath}/${operation.id}.pba" else operation.remotePath
            val local = staged.path
            return RsyncCommand(listOf(rsync.path, "-t", "--checksum", "--partial", "--no-links",
                "--no-owner", "--no-group", "--no-perms", "--protect-args", "--mkpath",
                "--info=progress2", "--outbuf=L", "--timeout=60",
                "--rsh=${StrictSshCommand.rsyncRemoteShell(client, ssh)}", "--") +
                if (upload) listOf(local, remote) else listOf(remote, local),
                StrictSshCommand.environment(ssh) + mapOf("LC_ALL" to "C"), RsyncOutputKind.INCREMENTAL_TRANSFER)
        }

        fun restoreCommand(rsync: File, client: File, ssh: StrictSshConfig, remote: RemoteRelativePath,
            destination: File, remoteBase: RemoteRelativePath, singleFile: Boolean = false): RsyncCommand {
            require(remoteBase.isSameOrAncestorOf(remote))
            require(destination.isAbsolute && destination.isDirectory && !java.nio.file.Files.isSymbolicLink(destination.toPath()))
            require(destination.name.startsWith("PiffBackup-restored-")) { "Restore must own a fresh directory" }
            return RsyncCommand(listOf(rsync.path, "-rt", "--checksum", "--ignore-existing", "--partial", "--partial-dir=.rsync-partial",
                "--no-links", "--no-owner", "--no-group", "--no-perms", "--protect-args", "--info=progress2",
                "--exclude=.piffbackup-versions/", "--outbuf=L", "--timeout=60",
                "--rsh=${StrictSshCommand.rsyncRemoteShell(client, ssh)}", "--",
                "${ssh.username}@${ssh.hostname}:${if (singleFile) remote.value else remote.pathWithTrailingSlash}", destination.path + "/"),
                StrictSshCommand.environment(ssh) + mapOf("LC_ALL" to "C"), RsyncOutputKind.INCREMENTAL_TRANSFER)
        }
    }
}
