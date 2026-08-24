package com.d35p4c1t0.piffbackup.rsync

import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RsyncCommandBuilderTest {
    private val shared = Files.createTempDirectory("piffbackup-command").toFile()
    private val mapping = BackupMapping(
        localRoot = CanonicalLocalRoot.create(File(shared, "Camera").apply { mkdirs() }.path, shared),
        remoteRoot = RemoteRelativePath.create("Bianca/Family's album 😄"),
    )
    private val ssh = StrictSshConfig(
        username = "u123456",
        hostname = "u123456.your-storagebox.de",
        port = 23,
        identityFile = File("/data/user/0/com.d35p4c1t0.piffbackup/cache/key"),
        sshHomeDirectory = File("/data/user/0/com.d35p4c1t0.piffbackup/files/ssh"),
    )
    private val builder = RsyncCommandBuilder(
        rsyncExecutable = File("/data/app/pkg/lib/arm64/libpiffbackup_rsync.so"),
        sshExecutable = File("/data/app/pkg/lib/arm64/libpiffbackup_dbclient.so"),
        remoteBasePath = RemoteRelativePath.create("Bianca"),
    )

    @Test
    fun `adoption preview preserves required non-destructive semantics`() {
        val command = builder.adoptionPreview(mapping, ssh)

        assertTrue("--dry-run" in command.arguments)
        assertTrue("--size-only" in command.arguments)
        assertTrue("--whole-file" in command.arguments)
        assertTrue("--protect-args" in command.arguments)
        assertEquals(2, command.arguments.count { it == "--itemize-changes" })
        assertFalse(command.arguments.any { it == "--delete" || it.startsWith("--delete-") })
        assertFalse("--checksum" in command.arguments)
        assertFalse("--compress" in command.arguments || "-z" in command.arguments)
        assertEquals(mapping.localRoot.pathWithTrailingSlash, command.arguments[command.arguments.lastIndex - 1])
        assertEquals(
            "u123456@u123456.your-storagebox.de:Bianca/Family's album 😄/",
            command.arguments.last(),
        )
        assertEquals("C", command.environment["LC_ALL"])
    }

    @Test
    fun `remote shell is controlled strict and key only`() {
        val command = builder.adoptionPreview(mapping, ssh)
        val remoteShell = command.arguments.single { it.startsWith("--rsh=") }

        assertTrue("StrictHostKeyChecking=yes" in remoteShell)
        assertTrue("BatchMode=yes" in remoteShell)
        assertTrue(" -p 23 " in remoteShell)
        assertTrue(" -I 60 " in remoteShell)
        assertFalse("StrictHostKeyChecking=no" in remoteShell)
        assertFalse(" -y" in remoteShell)
        assertFalse("u123456" in remoteShell)
    }

    @Test
    fun `confirmed adoption transfer removes dry run and enables total progress`() {
        val command = builder.adoptionTransfer(mapping, ssh)

        assertFalse("--dry-run" in command.arguments)
        assertTrue("--info=progress2" in command.arguments)
        assertEquals(1, command.arguments.count { it == "--itemize-changes" })
    }
}
