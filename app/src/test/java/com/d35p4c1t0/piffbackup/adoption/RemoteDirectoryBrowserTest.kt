package com.d35p4c1t0.piffbackup.adoption

import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteDirectoryBrowserTest {
    @Test
    fun `parser returns only safe immediate directory records`() {
        val output = listOf(
            "drwxr-xr-x          4,096 2026/08/24 22:14:49 Camera",
            "drwxr-xr-x          4,096 2026/08/24 22:14:49 Trips 😄/",
            "-rw-r--r--             15 2026/08/24 22:14:49 photo.jpg",
            "drwxr-xr-x          4,096 2026/08/24 22:14:49 ../escape",
            "unrelated output",
        ).joinToString("\n")

        assertEquals(
            listOf(
                RemoteDirectory("Camera", "Matteo/Camera"),
                RemoteDirectory("Trips 😄", "Matteo/Trips 😄"),
            ),
            RemoteDirectoryListParser.parse(RemoteRelativePath.create("Matteo"), output),
        )
    }

    @Test
    fun `listing command is strict protected and nonrecursive`() {
        val arguments = RemoteDirectoryListCommand.build(
            rsyncExecutable = File("/native/rsync"),
            sshExecutable = File("/native/dbclient"),
            ssh = StrictSshConfig(
                username = "u123456",
                hostname = "u123456.your-storagebox.de",
                port = 23,
                identityFile = File("/private/key"),
                sshHomeDirectory = File("/private/home"),
            ),
            parent = RemoteRelativePath.create("Matteo/Family's photos"),
        )

        assertTrue("--list-only" in arguments)
        assertTrue("--dirs" in arguments)
        assertTrue("--protect-args" in arguments)
        assertFalse(arguments.any { it.startsWith("--out-format=") })
        assertFalse("--recursive" in arguments || "-r" in arguments)
        assertFalse(arguments.any { it == "--delete" || it.startsWith("--delete-") })
        assertEquals("u123456@u123456.your-storagebox.de:Matteo/Family's photos/", arguments.last())
    }

    @Test
    fun `top level parser returns only valid backup roots`() {
        val output = listOf(
            "drwxr-xr-x          4,096 2026/08/24 22:14:49 Matteo/",
            "drwxr-xr-x          4,096 2026/08/24 22:14:49 Family photos/",
            "drwxr-xr-x          4,096 2026/08/24 22:14:49 backup_2026/",
        ).joinToString("\n")

        assertEquals(
            listOf(
                RemoteDirectory("backup_2026", "backup_2026"),
                RemoteDirectory("Matteo", "Matteo"),
            ),
            RemoteDirectoryListParser.parseTopLevel(output),
        )
    }

    @Test
    fun `top level listing addresses the account root read only`() {
        val arguments = RemoteDirectoryListCommand.buildTopLevel(
            rsyncExecutable = File("/native/rsync"),
            sshExecutable = File("/native/dbclient"),
            ssh = StrictSshConfig(
                username = "u123456",
                hostname = "u123456.your-storagebox.de",
                port = 23,
                identityFile = File("/private/key"),
                sshHomeDirectory = File("/private/home"),
            ),
        )

        assertEquals("u123456@u123456.your-storagebox.de:./", arguments.last())
        assertFalse(arguments.any { it == "--delete" || it.startsWith("--delete-") })
    }
}
