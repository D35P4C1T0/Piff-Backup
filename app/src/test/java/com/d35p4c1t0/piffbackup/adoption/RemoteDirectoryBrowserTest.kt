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
                RemoteDirectory("Camera", "Bianca/Camera"),
                RemoteDirectory("Trips 😄", "Bianca/Trips 😄"),
            ),
            RemoteDirectoryListParser.parse(RemoteRelativePath.create("Bianca"), output),
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
            parent = RemoteRelativePath.create("Bianca/Family's photos"),
        )

        assertTrue("--list-only" in arguments)
        assertTrue("--dirs" in arguments)
        assertTrue("--protect-args" in arguments)
        assertFalse(arguments.any { it.startsWith("--out-format=") })
        assertFalse("--recursive" in arguments || "-r" in arguments)
        assertFalse(arguments.any { it == "--delete" || it.startsWith("--delete-") })
        assertEquals("u123456@u123456.your-storagebox.de:Bianca/Family's photos/", arguments.last())
    }
}
