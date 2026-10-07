package com.d35p4c1t0.piffbackup.operations
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
class RestoreCommandTest {
    private val ssh = StrictSshConfig("user","nas.example",22,File("/private/key"),File("/private/ssh"))
    @Test fun restoreIsConfinedAndDoesNotOverwriteExistingFilesOrCopyLinks() {
        val root = Files.createTempDirectory("restore-command").toFile()
        try {
            val destination = root.resolve("PiffBackup-restored-test").apply { mkdir() }
            val command = RemoteOperationExecutor.restoreCommand(File("/bin/rsync"),File("/bin/ssh"),ssh,
                RemoteRelativePath.create("Backup/docs"),destination,RemoteRelativePath.create("Backup"))
            assertTrue(command.arguments.contains("--ignore-existing"))
            assertTrue(command.arguments.contains("--no-links"))
            assertFalse(command.arguments.any { it.startsWith("--delete") })
            assertEquals("user@nas.example:Backup/docs/",command.arguments[command.arguments.lastIndex-1])
            assertTrue(runCatching { RemoteOperationExecutor.restoreCommand(File("/bin/rsync"),File("/bin/ssh"),ssh,
                RemoteRelativePath.create("outside"),destination,RemoteRelativePath.create("Backup")) }.isFailure)
            val userFolder = root.resolve("existing").apply { mkdir() }
            assertTrue(runCatching { RemoteOperationExecutor.restoreCommand(File("/bin/rsync"),File("/bin/ssh"),ssh,
                RemoteRelativePath.create("Backup"),userFolder,RemoteRelativePath.create("Backup")) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
