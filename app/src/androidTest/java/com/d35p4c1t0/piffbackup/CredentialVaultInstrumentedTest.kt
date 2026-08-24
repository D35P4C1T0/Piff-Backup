package com.d35p4c1t0.piffbackup

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.onboarding.HostKeyPin
import com.d35p4c1t0.piffbackup.onboarding.KnownHostStore
import com.d35p4c1t0.piffbackup.onboarding.NativeOnboardingCredentialManager
import com.d35p4c1t0.piffbackup.security.EncryptedCredentialVault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialVaultInstrumentedTest {
    @Test
    fun privateKeyIsEncryptedAndOnlyMaterializedAsOwnerOnlyTemporaryFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = EncryptedCredentialVault(context)
        val profileId = "vault-${System.nanoTime()}"
        val reference = vault.referenceFor(profileId)
        val plaintext = byteArrayOf(1, 4, 9, 16, 25, 36)
        val source = File(context.cacheDir, "$profileId.key").apply { writeBytes(plaintext) }
        var temporaryPath: String? = null

        try {
            vault.store(reference, source)
            source.delete()

            vault.withDecryptedKey(reference) { temporary ->
                temporaryPath = temporary.path
                assertArrayEquals(plaintext, temporary.readBytes())
                assertEquals(OWNER_FILE_MODE, Os.stat(temporary.path).st_mode and PERMISSION_MASK)
            }

            assertTrue(vault.contains(reference))
            assertFalse(File(requireNotNull(temporaryPath)).exists())
        } finally {
            source.delete()
            plaintext.fill(0)
        }
    }

    @Test
    fun launchCleanupDeletesOnlyRecognizedTemporaryKeyFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = EncryptedCredentialVault(context)
        val temporaryRoot = File(context.noBackupFilesDir, "credentials/temporary")
        val abandoned = File(temporaryRoot, "key-abandoned.tmp").apply { writeText("secret") }
        val unrelated = File(temporaryRoot, "unrelated.keep").apply { writeText("keep") }

        try {
            assertEquals(1, vault.cleanupAbandonedTemporaryKeys())
            assertFalse(abandoned.exists())
            assertTrue(unrelated.isFile)
        } finally {
            abandoned.delete()
            unrelated.delete()
        }
    }

    @Test
    fun packagedKeyToolCreatesReusableKeystoreProtectedEd25519Credential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = EncryptedCredentialVault(context)
        val manager = NativeOnboardingCredentialManager(context, vault)
        val profileId = "native-key-${System.nanoTime()}"

        val first = manager.ensure(profileId, null)
        val second = manager.ensure(profileId, first.reference)

        assertTrue(first.publicKeyLine.startsWith("ssh-ed25519 "))
        assertEquals(first.publicKeyLine, second.publicKeyLine)
        manager.withPrivateKey(first.reference) { privateKey ->
            assertTrue(privateKey.isFile)
            assertEquals(OWNER_FILE_MODE, Os.stat(privateKey.path).st_mode and PERMISSION_MASK)
        }
    }

    @Test
    fun knownHostStoreWritesOneExactOwnerOnlyPin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileId = "known-host-${System.nanoTime()}"
        val pin = HostKeyPin.parse("ssh-ed25519 AQID")

        val home = KnownHostStore(context).write(profileId, "box.example", pin)
        val knownHosts = File(home, ".ssh/known_hosts")

        assertEquals("box.example ssh-ed25519 AQID\n", knownHosts.readText())
        assertEquals(OWNER_FILE_MODE, Os.stat(knownHosts.path).st_mode and PERMISSION_MASK)
    }

    private companion object {
        const val OWNER_FILE_MODE = 384 // 0600
        const val PERMISSION_MASK = 511 // 0777
    }
}
