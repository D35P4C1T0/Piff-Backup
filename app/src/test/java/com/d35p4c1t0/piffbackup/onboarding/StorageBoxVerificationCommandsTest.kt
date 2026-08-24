package com.d35p4c1t0.piffbackup.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StorageBoxVerificationCommandsTest {
    @Test
    fun `destination verification is read only and fixed to Bianca`() {
        assertEquals(listOf("pwd"), StorageBoxVerificationCommands.AUTHENTICATION_CHECK)
        assertEquals(listOf("ls", "-d", "Bianca/"), StorageBoxVerificationCommands.DESTINATION_CHECK)

        val allArguments = StorageBoxVerificationCommands.AUTHENTICATION_CHECK +
            StorageBoxVerificationCommands.DESTINATION_CHECK
        assertFalse(allArguments.any { it in setOf("rm", "rmdir", "mv", "touch", "mkdir") })
        assertFalse(allArguments.any { it.contains("--delete") })
    }
}
