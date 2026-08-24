package com.d35p4c1t0.piffbackup.rsync

import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.BackupMappingValidator
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath

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

    fun adoptionPreview(mapping: BackupMapping, ssh: StrictSshConfig): RsyncCommand =
        adoption(mapping, ssh, dryRun = true)

    fun adoptionTransfer(mapping: BackupMapping, ssh: StrictSshConfig): RsyncCommand =
        adoption(mapping, ssh, dryRun = false)

    private fun adoption(
        mapping: BackupMapping,
        ssh: StrictSshConfig,
        dryRun: Boolean,
    ): RsyncCommand {
        BackupMappingValidator.validate(listOf(mapping), remoteBasePath)
        require(mapping.localRoot.file.isDirectory && mapping.localRoot.file.canRead()) {
            "Local root must be an accessible directory"
        }
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

    companion object {
        const val ITEM_RECORD_PREFIX = "PIFFBACKUP-ITEM:"
        private const val IO_TIMEOUT_SECONDS = 60
    }
}
