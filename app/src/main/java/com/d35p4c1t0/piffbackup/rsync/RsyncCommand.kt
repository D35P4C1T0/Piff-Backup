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
    fun preservingVersions(versionId: String): RsyncCommand {
        require(Regex("[A-Za-z0-9._-]{1,128}").matches(versionId))
        val split = arguments.indexOf("--")
        require(split >= 0)
        return copy(arguments = arguments.take(split) +
            listOf("--backup", "--backup-dir=.piffbackup-versions/$versionId") + arguments.drop(split))
    }
    init {
        require(arguments.isNotEmpty()) { "Rsync command must not be empty" }
        require(arguments.none { '\u0000' in it }) { "Rsync arguments must not contain NUL" }
        require(arguments.none { it == "--delete" || it.startsWith("--delete-") }) {
            "Destructive rsync options are forbidden"
        }
    }
}

enum class RsyncComparisonPolicy(val option: String) {
    INITIAL_SIZE_MATCH("--size-only"),
    CONTENT("--checksum"),
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
        comparison: RsyncComparisonPolicy = RsyncComparisonPolicy.INITIAL_SIZE_MATCH,
    ): RsyncCommand = adoption(mapping, ssh, fileList, dryRun = true, comparison)

    fun adoptionTransfer(
        mapping: BackupMapping,
        ssh: StrictSshConfig,
        fileList: java.io.File? = null,
        comparison: RsyncComparisonPolicy = RsyncComparisonPolicy.INITIAL_SIZE_MATCH,
    ): RsyncCommand = adoption(mapping, ssh, fileList, dryRun = false, comparison)

    fun incrementalTransfer(
        transfer: PlannedMediaTransfer,
        ssh: StrictSshConfig,
    ): RsyncCommand = incrementalTransfer(
        mapping = transfer.mapping.mapping,
        fileList = transfer.fileList,
        ssh = ssh,
    )

    fun incrementalTransfer(
        mapping: BackupMapping,
        fileList: java.io.File,
        ssh: StrictSshConfig,
    ): RsyncCommand {
        validateMappingAndLocalRoot(mapping)
        require(fileList.isAbsolute) { "Incremental file list must be absolute" }
        require(fileList.isFile && fileList.canRead() && fileList.length() > 0L) {
            "Incremental file list must be readable and non-empty"
        }
        val options = mutableListOf(
            rsyncExecutable.path,
            "-rt",
            "--no-links",
            "--from0",
            "--files-from=${fileList.path}",
            "--checksum",
            "--no-whole-file",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--no-owner",
            "--no-group",
            "--no-perms",
            "--protect-args",
            "--itemize-changes",
            "--stats",
            "--outbuf=L",
            "--out-format=$ITEM_RECORD_FORMAT",
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
        comparison: RsyncComparisonPolicy,
    ): RsyncCommand {
        validateMappingAndLocalRoot(mapping)
        val options = mutableListOf(
            rsyncExecutable.path,
            "-rt",
            "--no-links",
            comparison.option,
            "--no-whole-file",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--no-owner",
            "--no-group",
            "--no-perms",
            "--protect-args",
            "--itemize-changes",
            "--stats",
            "--outbuf=L",
            "--out-format=$ITEM_RECORD_FORMAT",
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
        const val ITEM_RECORD_PREFIX = "PIFFBACKUP-FILE:"
        const val ITEM_RECORD_FORMAT = "$ITEM_RECORD_PREFIX%i:%l:%b:%n"
        private const val IO_TIMEOUT_SECONDS = 60
    }
}
