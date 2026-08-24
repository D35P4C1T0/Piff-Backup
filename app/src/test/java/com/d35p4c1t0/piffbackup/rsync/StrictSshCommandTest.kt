package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StrictSshCommandTest {
    private val config = StrictSshConfig(
        username = "u123456",
        hostname = "u123456.your-storagebox.de",
        port = 23,
        identityFile = File("/data/user/0/com.d35p4c1t0.piffbackup/cache/key"),
        sshHomeDirectory = File("/data/user/0/com.d35p4c1t0.piffbackup/files/ssh"),
    )

    @Test
    fun `requires strict host verification and key-only batch authentication`() {
        val arguments = StrictSshCommand.dbclientArguments(config)

        assertTrue(arguments.windowed(2).contains(listOf("-o", "StrictHostKeyChecking=yes")))
        assertTrue(arguments.windowed(2).contains(listOf("-o", "BatchMode=yes")))
        assertFalse(arguments.any { it == "-y" || it.contains("StrictHostKeyChecking=no") })
        assertEquals("u123456@u123456.your-storagebox.de", arguments.last())
        assertEquals(mapOf("HOME" to config.sshHomeDirectory.path), StrictSshCommand.environment(config))
    }

    @Test
    fun `rejects injection-shaped identity paths in rsync remote shell`() {
        val unsafe = config.copy(identityFile = File("/data/user/0/pkg/key path"))

        assertThrows(IllegalArgumentException::class.java) {
            StrictSshCommand.rsyncRemoteShell(File("/data/app/pkg/lib/arm64/libpiffbackup_dbclient.so"), unsafe)
        }
    }

    @Test
    fun `rejects invalid connection fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictSshCommand.dbclientArguments(config.copy(username = "user;command"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictSshCommand.dbclientArguments(config.copy(hostname = "host name"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictSshCommand.dbclientArguments(config.copy(port = 0))
        }
    }
}
