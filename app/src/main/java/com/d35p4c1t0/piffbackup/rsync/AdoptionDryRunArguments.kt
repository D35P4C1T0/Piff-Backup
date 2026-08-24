package com.d35p4c1t0.piffbackup.rsync

import java.io.File

object AdoptionDryRunArguments {
    fun local(sourceRoot: File, destinationRoot: File): List<String> {
        require(sourceRoot.isAbsolute) { "Source root must be absolute" }
        require(destinationRoot.isAbsolute) { "Destination root must be absolute" }
        return listOf(
            "-rlt",
            "--size-only",
            "--whole-file",
            "--dry-run",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--no-owner",
            "--no-group",
            "--no-perms",
            "--protect-args",
            "--itemize-changes",
            "--stats",
            sourceRoot.path.trimEnd('/') + "/",
            destinationRoot.path.trimEnd('/') + "/",
        )
    }
}
