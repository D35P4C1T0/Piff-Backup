package com.d35p4c1t0.piffbackup.onboarding

import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageBoxVerificationCommandsTest {
    @Test
    fun `destination verification is read only and uses the selected folder`() {
        assertEquals(listOf("pwd"), StorageBoxVerificationCommands.AUTHENTICATION_CHECK)
        val destinationCheck = StorageBoxVerificationCommands.destinationCheck(
            RemoteRelativePath.create("Backups"),
        )
        assertEquals(listOf("ls", "-d", "Backups/"), destinationCheck)

        val allArguments = StorageBoxVerificationCommands.AUTHENTICATION_CHECK +
            destinationCheck
        assertFalse(allArguments.any { it in setOf("rm", "rmdir", "mv", "touch", "mkdir") })
        assertFalse(allArguments.any { it.contains("--delete") })
    }

    @Test
    fun `destination check rejects remote shell syntax`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            StorageBoxVerificationCommands.destinationCheck(
                RemoteRelativePath.create("Backups;touch"),
            )
        }
    }

    @Test
    fun `native ssh diagnostic redacts endpoint and private paths`() {
        val config = StrictSshConfig(
            username = "u123456",
            hostname = "u123456.your-storagebox.de",
            port = 23,
            identityFile = File("/private/key-123"),
            sshHomeDirectory = File("/private/ssh-home"),
        )

        val diagnostic = safeNativeSshDiagnostic(
            "Host u123456.your-storagebox.de unknown; key /private/key-123; home /private/ssh-home",
            config,
        )

        assertFalse(diagnostic.contains("u123456"))
        assertFalse(diagnostic.contains("/private"))
        assertTrue(diagnostic.contains("<hostname>"))
        assertTrue(diagnostic.contains("<identity>"))
        assertTrue(diagnostic.contains("<ssh-home>"))
    }
}
