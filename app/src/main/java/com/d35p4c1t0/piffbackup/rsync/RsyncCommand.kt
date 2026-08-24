package com.d35p4c1t0.piffbackup.rsync

import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.BackupMappingValidator
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.media.PlannedMediaTransfer

data class RsyncCommand(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val outputKind: RsyncOutputKind,
) {
    init {
        require(arguments.isNotEmpty()) { "Rsync command must not be empty" }
        require(arguments.none { '\u0000' in it }) { "Rsync arguments must not contain NUL" }
        require(arguments.none { it == "--delete" || it.startsWith("--delete-") }) {
            "Destructive rsync options are forbidden"
        }
    }
}

enum class RsyncOutputKind {
    ADOPTION_PREVIEW,
    ADOPTION_TRANSFER,
    INCREMENTAL_TRANSFER,
}

class RsyncCommandBuilder(
    private val rsyncExecutable: java.io.File,
    private val sshExecutable: java.io.File,
    private val remoteBasePath: RemoteRelativePath,
) {
    init {
        require(rsyncExecutable.isAbsolute) { "Rsync executable must be absolute" }
        require(sshExecutable.isAbsolute) { "SSH executable must be absolute" }
    }

    fun adoptionPreview(
        mapping: BackupMapping,
        ssh: StrictSshConfig,
        fileList: java.io.File? = null,
    ): RsyncCommand = adoption(mapping, ssh, fileList, dryRun = true)

    fun adoptionTransfer(
        mapping: BackupMapping,
        ssh: StrictSshConfig,
        fileList: java.io.File? = null,
    ): RsyncCommand = adoption(mapping, ssh, fileList, dryRun = false)

    fun incrementalTransfer(
        transfer: PlannedMediaTransfer,
        ssh: StrictSshConfig,
    ): RsyncCommand {
        val mapping = transfer.mapping.mapping
        validateMappingAndLocalRoot(mapping)
        require(transfer.fileList.isAbsolute) { "Incremental file list must be absolute" }
        require(transfer.fileList.isFile && transfer.fileList.canRead() && transfer.fileList.length() > 0L) {
            "Incremental file list must be readable and non-empty"
        }
        val options = mutableListOf(
            rsyncExecutable.path,
            "-rlt",
            "--from0",
            "--files-from=${transfer.fileList.path}",
            "--whole-file",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--no-owner",
            "--no-group",
            "--no-perms",
            "--protect-args",
            "--itemize-changes",
            "--stats",
            "--outbuf=L",
            "--out-format=$ITEM_RECORD_PREFIX%i:%l",
            "--timeout=$IO_TIMEOUT_SECONDS",
            "--rsh=${StrictSshCommand.rsyncRemoteShell(sshExecutable, ssh)}",
            "--info=progress2",
            "--",
            mapping.localRoot.pathWithTrailingSlash,
            "${ssh.username}@${ssh.hostname}:${mapping.remoteRoot.pathWithTrailingSlash}",
        )
        return RsyncCommand(
            arguments = options,
            environment = StrictSshCommand.environment(ssh) + mapOf("LC_ALL" to "C"),
            outputKind = RsyncOutputKind.INCREMENTAL_TRANSFER,
        )
    }

    private fun adoption(
        mapping: BackupMapping,
        ssh: StrictSshConfig,
        fileList: java.io.File?,
        dryRun: Boolean,
    ): RsyncCommand {
        validateMappingAndLocalRoot(mapping)
        val options = mutableListOf(
            rsyncExecutable.path,
            "-rlt",
            "--size-only",
            "--whole-file",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--no-owner",
            "--no-group",
            "--no-perms",
            "--protect-args",
            "--itemize-changes",
            "--stats",
            "--outbuf=L",
            "--out-format=$ITEM_RECORD_PREFIX%i:%l",
            "--timeout=$IO_TIMEOUT_SECONDS",
            "--rsh=${StrictSshCommand.rsyncRemoteShell(sshExecutable, ssh)}",
        )
        if (fileList != null) {
            require(fileList.isAbsolute && fileList.isFile && fileList.canRead() && fileList.length() > 0L) {
                "Adoption file list must be readable and non-empty"
            }
            options += "--from0"
            options += "--files-from=${fileList.path}"
        }
        if (dryRun) {
            options += "--dry-run"
            // A second itemize option makes unchanged entries observable, so
            // the adoption summary can count already-backed-up regular files.
            options += "--itemize-changes"
        } else {
            options += "--info=progress2"
        }
        options += "--"
        options += mapping.localRoot.pathWithTrailingSlash
        options += "${ssh.username}@${ssh.hostname}:${mapping.remoteRoot.pathWithTrailingSlash}"
        return RsyncCommand(
            arguments = options,
            environment = StrictSshCommand.environment(ssh) + mapOf("LC_ALL" to "C"),
            outputKind = if (dryRun) RsyncOutputKind.ADOPTION_PREVIEW else RsyncOutputKind.ADOPTION_TRANSFER,
        )
    }

    private fun validateMappingAndLocalRoot(mapping: BackupMapping) {
        BackupMappingValidator.validate(listOf(mapping), remoteBasePath)
        require(mapping.localRoot.file.isDirectory && mapping.localRoot.file.canRead()) {
            "Local root must be an accessible directory"
        }
    }

    companion object {
        const val ITEM_RECORD_PREFIX = "PIFFBACKUP-ITEM:"
        private const val IO_TIMEOUT_SECONDS = 60
    }
}
